package android.view;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Compile-time stub for Android InputChannel passed to InputEventReceiver.
 */
public final class InputChannel implements Parcelable {
    public static final Creator<InputChannel> CREATOR = null;

    public String getName() {
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
