package pochita.sideveil.build;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.gradle.api.provider.ProviderFactory;

/**
 * Shared Android SDK path resolution helpers for non-AGP subprojects (:hidden-stubs, :sensor).
 */
public final class AndroidSdkPaths {
    private static final List<String> PREFERRED_PLATFORMS =
            List.of("android-37.2", "android-37.0", "android-37", "android-36");

    private AndroidSdkPaths() {}

    public static File resolveSdkRoot(ProviderFactory providers, String moduleName) {
        String sdkPath = firstNonBlank(
                providers.environmentVariable("ANDROID_HOME").getOrNull(),
                providers.environmentVariable("ANDROID_SDK_ROOT").getOrNull(),
                System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"));
        if (sdkPath == null) {
            throw new IllegalStateException(
                    "ANDROID_HOME or ANDROID_SDK_ROOT must be set to build " + moduleName);
        }
        return new File(sdkPath);
    }

    public static File resolveAndroidJar(ProviderFactory providers, String moduleName) {
        File sdkRoot = resolveSdkRoot(providers, moduleName);
        File platformsDir = new File(sdkRoot, "platforms");
        for (String name : PREFERRED_PLATFORMS) {
            File candidate = new File(platformsDir, name + "/android.jar");
            if (candidate.isFile()) {
                return candidate;
            }
        }
        File[] children = platformsDir.listFiles();
        if (children != null) {
            return Arrays.stream(children)
                    .map(dir -> new File(dir, "android.jar"))
                    .filter(File::isFile)
                    .max(Comparator.comparing(f -> f.getParentFile().getName()))
                    .orElseThrow(() -> new IllegalStateException(
                            "No android.jar found under " + platformsDir.getAbsolutePath()));
        }
        throw new IllegalStateException("No android.jar found under " + platformsDir.getAbsolutePath());
    }

    public static File resolveD8(ProviderFactory providers, String moduleName) {
        File sdkRoot = resolveSdkRoot(providers, moduleName);
        File buildToolsDir = new File(sdkRoot, "build-tools");
        File[] children = buildToolsDir.listFiles();
        if (children != null) {
            return Arrays.stream(children)
                    .sorted(Comparator.comparing(File::getName).reversed())
                    .map(dir -> new File(dir, "d8"))
                    .filter(f -> f.isFile() && f.canExecute())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No executable d8 found under " + buildToolsDir.getAbsolutePath()));
        }
        throw new IllegalStateException("No executable d8 found under " + buildToolsDir.getAbsolutePath());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
