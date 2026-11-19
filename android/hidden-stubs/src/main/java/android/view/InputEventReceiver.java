package android.view;

import android.os.Looper;

/**
 * Compile-time stub for Android's native input channel event receiver.
 */
public abstract class InputEventReceiver {
    public InputEventReceiver(InputChannel inputChannel, Looper looper) {
        throw new UnsupportedOperationException("Stub");
    }

    public void onInputEvent(InputEvent event) {
    }

    public final void finishInputEvent(InputEvent event, boolean handled) {
        throw new UnsupportedOperationException("Stub");
    }

    public void dispose() {
        throw new UnsupportedOperationException("Stub");
    }
}
