package app.hushmessenger.extension;

import android.content.Context;
import android.content.Intent;

/**
 * Opens the phone's own gallery app for the chat's gallery button. What the person picks comes back under the request
 * code Messenger reads a photo from another app under, so the chat's own code copies it and opens it in Messenger's
 * editor for that chat.
 */
final class GalleryPicker {
    static final String KEY = "external_gallery";
    /** Set on the pick intent, so its launch gets the chat's request code for a picked photo. */
    static final String PICK_MARK = "app.hushmessenger.gallery_pick";

    private GalleryPicker() {}

    /** A pick for a photo or a video when some app on the phone answers it, otherwise null. */
    static Intent pickIntent(Context context) {
        if (context == null) return null;
        Intent pick = new Intent(Intent.ACTION_PICK).setType("image/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "video/*"});
        if (pick.resolveActivity(context.getPackageManager()) == null) return null;
        return pick.putExtra(PICK_MARK, true);
    }

    static boolean isPick(Intent intent) {
        return intent != null && Intent.ACTION_PICK.equals(intent.getAction()) && intent.getBooleanExtra(PICK_MARK, false);
    }
}
