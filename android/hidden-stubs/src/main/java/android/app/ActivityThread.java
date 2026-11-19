package android.app;

import android.content.Context;

/**
 * Compile-time stub for obtaining the system context in privileged app_process daemons.
 */
public final class ActivityThread {
    public static ActivityThread systemMain() {
        throw new UnsupportedOperationException("Stub");
    }

    public ContextImpl getSystemContext() {
        throw new UnsupportedOperationException("Stub");
    }

    public Context getSystemUiContext() {
        throw new UnsupportedOperationException("Stub");
    }
}
