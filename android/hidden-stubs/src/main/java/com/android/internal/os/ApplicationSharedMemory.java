package com.android.internal.os;

/**
 * Compile-time stub for Android 15+ shared application memory initialization.
 */
public class ApplicationSharedMemory implements AutoCloseable {
    public static ApplicationSharedMemory create() {
        throw new UnsupportedOperationException("Stub");
    }

    public static void setInstance(ApplicationSharedMemory instance) {
        throw new UnsupportedOperationException("Stub");
    }

    public static ApplicationSharedMemory getInstance() {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public void close() {
    }
}
