//! Minimal JNI bridge (`libsideveil_hud.so`) rasterizing declarative `NativeHudScene` JSON
//! directly into Android `Bitmap.Config.ARGB_8888` memory via `sideveil-gfx` (`vello_cpu` NEON)
//! and NDK `libjnigraphics.so`.

use std::cell::RefCell;

use sideveil_gfx::hud_renderer::HudCanvas;
use sideveil_protocol::hud::NativeHudScene;

thread_local! {
    static HUD_CANVAS: RefCell<HudCanvas> = RefCell::new(HudCanvas::default());
}

/// Decodes a JNI UTF-16 code-unit slice into standard Rust UTF-8, preserving
/// supplementary Unicode surrogate pairs (such as emoji) that JNI MUTF-8 breaks.
#[must_use]
pub fn decode_jni_utf16(units: &[u16]) -> String {
    String::from_utf16(units).unwrap_or_else(|_| String::from_utf16_lossy(units))
}

/// Pure-Rust scene decode + rasterization entrypoint shared by JNI and host unit tests.
pub fn render_scene_json_into(
    scene_json: &str,
    density: f32,
    width_px: u16,
    height_px: u16,
    stride_bytes: usize,
    pixels: &mut [u8],
) -> bool {
    let Ok(scene) = serde_json::from_str::<NativeHudScene>(scene_json) else {
        return false;
    };
    let row_bytes = usize::from(width_px) * 4;
    let total_rows = usize::from(height_px);
    if row_bytes == 0 || total_rows == 0 || stride_bytes < row_bytes {
        return false;
    }
    let Some(min_buf) = stride_bytes.checked_mul(total_rows) else {
        return false;
    };
    if pixels.len() < min_buf {
        return false;
    }

    HUD_CANVAS.with(|cell| {
        let mut canvas = cell.borrow_mut();
        if stride_bytes == row_bytes {
            canvas
                .render_into(&scene, density, width_px, height_px, &mut pixels[..min_buf])
                .is_ok()
        } else {
            let mut packed = vec![0u8; row_bytes * total_rows];
            if canvas
                .render_into(&scene, density, width_px, height_px, &mut packed)
                .is_err()
            {
                return false;
            }
            for row in 0..total_rows {
                let src_off = row * row_bytes;
                let dst_off = row * stride_bytes;
                pixels[dst_off..dst_off + row_bytes]
                    .copy_from_slice(&packed[src_off..src_off + row_bytes]);
            }
            true
        }
    })
}

#[cfg(target_os = "android")]
#[allow(non_snake_case)]
mod android_jni {
    use std::ffi::c_void;
    use std::ptr;

    use jni_sys::{JNI_FALSE, JNI_TRUE, JNIEnv, jboolean, jclass, jfloat, jobject, jstring};

    use super::{HUD_CANVAS, decode_jni_utf16, render_scene_json_into};
    use sideveil_gfx::hud_renderer::HudCanvas;

    const ANDROID_BITMAP_RESULT_SUCCESS: i32 = 0;
    const ANDROID_BITMAP_FORMAT_RGBA_8888: i32 = 1;

    #[repr(C)]
    #[derive(Default)]
    struct AndroidBitmapInfo {
        width: u32,
        height: u32,
        stride: u32,
        format: i32,
        flags: u32,
    }

    unsafe extern "C" {
        fn AndroidBitmap_getInfo(
            env: *mut JNIEnv,
            jbitmap: jobject,
            info: *mut AndroidBitmapInfo,
        ) -> i32;
        fn AndroidBitmap_lockPixels(
            env: *mut JNIEnv,
            jbitmap: jobject,
            addr_ptr: *mut *mut c_void,
        ) -> i32;
        fn AndroidBitmap_unlockPixels(env: *mut JNIEnv, jbitmap: jobject) -> i32;
    }

    /// JNI entrypoint invoked by `pochita.sideveil.host.NativeHudRenderer.nativeRenderScene`.
    ///
    /// # Safety
    ///
    /// Caller (ART JNI bridge) must pass a valid `JNIEnv`, a valid Java `String` (`scene_json`),
    /// and a valid `android.graphics.Bitmap` (`bitmap`) or null pointers (which return `JNI_FALSE`).
    #[unsafe(no_mangle)]
    pub unsafe extern "system" fn Java_pochita_sideveil_host_NativeHudRenderer_nativeRenderScene(
        env: *mut JNIEnv,
        _clazz: jclass,
        scene_json: jstring,
        bitmap: jobject,
        density: jfloat,
    ) -> jboolean {
        if env.is_null() || scene_json.is_null() || bitmap.is_null() {
            return JNI_FALSE;
        }
        // SAFETY: `env` was checked non-null; JNIEnv points to a valid JNINativeInterface table.
        let vtable = unsafe { *env };
        if vtable.is_null() {
            return JNI_FALSE;
        }

        // SAFETY: `scene_json` is a valid non-null Java String; returns UTF-16 code unit count.
        let utf16_len = unsafe { ((*vtable).v1_1.GetStringLength)(env, scene_json) };
        let Ok(len) = usize::try_from(utf16_len) else {
            return JNI_FALSE;
        };
        let json_str = if len == 0 {
            String::new()
        } else {
            // SAFETY: `scene_json` is a valid non-null Java String reference provided by ART.
            let chars_ptr =
                unsafe { ((*vtable).v1_1.GetStringChars)(env, scene_json, ptr::null_mut()) };
            if chars_ptr.is_null() {
                return JNI_FALSE;
            }
            // SAFETY: `chars_ptr` points to `len` UTF-16 code units until ReleaseStringChars.
            let units = unsafe { std::slice::from_raw_parts(chars_ptr, len) };
            let decoded = decode_jni_utf16(units);
            // SAFETY: Matches the preceding GetStringChars call on the same env and jstring.
            unsafe { ((*vtable).v1_1.ReleaseStringChars)(env, scene_json, chars_ptr) };
            decoded
        };

        let mut info = AndroidBitmapInfo::default();
        // SAFETY: `env`, `bitmap`, and `&mut info` are valid non-null pointers for libjnigraphics.
        if unsafe { AndroidBitmap_getInfo(env, bitmap, &mut info) } != ANDROID_BITMAP_RESULT_SUCCESS
        {
            return JNI_FALSE;
        }
        if info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 {
            return JNI_FALSE;
        }
        let (Ok(w), Ok(h)) = (u16::try_from(info.width), u16::try_from(info.height)) else {
            return JNI_FALSE;
        };
        let stride = info.stride as usize;
        let Some(total_bytes) = stride.checked_mul(usize::from(h)) else {
            return JNI_FALSE;
        };

        let mut pixel_ptr: *mut c_void = ptr::null_mut();
        // SAFETY: Locks Android Bitmap backing buffer for direct CPU writes.
        if unsafe { AndroidBitmap_lockPixels(env, bitmap, &mut pixel_ptr) }
            != ANDROID_BITMAP_RESULT_SUCCESS
            || pixel_ptr.is_null()
        {
            return JNI_FALSE;
        }

        // SAFETY: `pixel_ptr` is locked by libjnigraphics for at least `stride * height` bytes.
        let pixel_slice =
            unsafe { std::slice::from_raw_parts_mut(pixel_ptr.cast::<u8>(), total_bytes) };
        let ok = render_scene_json_into(&json_str, density, w, h, stride, pixel_slice);

        // SAFETY: Unlocks the previously locked Android Bitmap backing buffer.
        let _ = unsafe { AndroidBitmap_unlockPixels(env, bitmap) };
        if ok { JNI_TRUE } else { JNI_FALSE }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_pochita_sideveil_host_NativeHudRenderer_nativeResetCache(
        _env: *mut JNIEnv,
        _clazz: jclass,
    ) {
        HUD_CANVAS.with(|cell| {
            *cell.borrow_mut() = HudCanvas::default();
        });
    }
}

#[cfg(target_os = "android")]
pub use android_jni::{
    Java_pochita_sideveil_host_NativeHudRenderer_nativeRenderScene,
    Java_pochita_sideveil_host_NativeHudRenderer_nativeResetCache,
};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn renders_json_scene_into_packed_and_strided_buffers() {
        let json = r#"{
            "pluginId": "demo.hud",
            "widthDp": 120,
            "heightDp": 48,
            "primitives": [
                {"kind": "roundedPill", "x": 2, "y": 2, "w": 116, "h": 44, "radius": 12, "fillArgb": 4280361249}
            ],
            "texts": []
        }"#;
        let mut packed = vec![0u8; 120 * 48 * 4];
        assert!(render_scene_json_into(
            json,
            1.0,
            120,
            48,
            120 * 4,
            &mut packed
        ));
        assert!(packed.as_chunks::<4>().0.iter().any(|px| px[3] > 200));

        // Strided buffer (+16 bytes per row padding).
        let stride = 120 * 4 + 16;
        let mut strided = vec![0u8; stride * 48];
        assert!(render_scene_json_into(
            json,
            1.0,
            120,
            48,
            stride,
            &mut strided
        ));
        assert!(strided[stride * 24 + 60 * 4 + 3] > 200);
    }

    #[test]
    fn decodes_utf16_surrogate_pairs_and_renders_emoji_hud_scene() {
        let json = r#"{
            "pluginId": "demo.hud",
            "widthDp": 120,
            "heightDp": 48,
            "primitives": [
                {"kind": "roundedPill", "x": 2, "y": 2, "w": 116, "h": 44, "radius": 12, "fillArgb": 4280361249}
            ],
            "texts": [
                {"x": 8, "y": 20, "text": "🔥 CPU 42% 🚀 中文", "sizeSp": 12, "colorArgb": 4294967295}
            ]
        }"#;
        let utf16_units: Vec<u16> = json.encode_utf16().collect();
        // Verify surrogate pairs exist in UTF-16 representation (emoji take 2 code units each).
        assert!(utf16_units.len() > json.chars().count());
        let decoded = decode_jni_utf16(&utf16_units);
        assert_eq!(decoded, json);

        let mut packed = vec![0u8; 120 * 48 * 4];
        assert!(render_scene_json_into(
            &decoded,
            1.0,
            120,
            48,
            120 * 4,
            &mut packed
        ));
        assert!(packed.as_chunks::<4>().0.iter().any(|px| px[3] > 200));

        // Lone surrogate degrades gracefully without panicking.
        let lone = [0x0041u16, 0xD83Du16, 0x0042u16];
        assert_eq!(decode_jni_utf16(&lone), "A\u{FFFD}B");
    }
}
