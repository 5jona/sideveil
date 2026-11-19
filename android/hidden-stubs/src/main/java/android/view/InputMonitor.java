package android.view;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Compile-time stub for Android InputMonitor handle returned by monitorGestureInput.
 */
public final class InputMonitor implements Parcelable {
    public static final Creator<InputMonitor> CREATOR = null;

    public InputChannel getInputChannel() {
        throw new UnsupportedOperationException("Stub");
    }

    public void pilferPointers() {
        throw new UnsupportedOperationException("Stub");
    }

    public void dispose() {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
    }
}
