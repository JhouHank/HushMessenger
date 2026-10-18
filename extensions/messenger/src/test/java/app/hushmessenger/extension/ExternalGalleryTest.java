package app.hushmessenger.extension;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class ExternalGalleryTest {
    static final String CAMERA_SCREEN = "com.facebook.messaging.montage.composer.MontageComposerActivity";
    Application app;

    @Before public void reset() {
        app = RuntimeEnvironment.getApplication();
        Settings.initialize(app);
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        Settings.hookErrors.clear();
        CrashGuard.resetForTests();
    }

    /** A gallery app that answers a photo pick, as Samsung's Gallery and Google Photos do. */
    private void installGallery() {
        ResolveInfo gallery = new ResolveInfo();
        gallery.activityInfo = new ActivityInfo();
        gallery.activityInfo.packageName = "com.example.gallery";
        gallery.activityInfo.name = "com.example.gallery.Pick";
        shadowOf(app.getPackageManager()).addResolveInfoForIntent(new Intent(Intent.ACTION_PICK).setType("image/*"), gallery);
    }

    /** The chat camera launcher's start: its own extras, then the ones it's handed, on a new intent for its screen. */
    private Intent launch(Bundle handed) {
        Intent intent = new Intent().setClassName(app.getPackageName(), CAMERA_SCREEN).putExtra("trigger2", "thread");
        return handed == null ? intent : intent.putExtras(handed);
    }

    @Test public void theGalleryButtonOpensThePhonesGalleryOnlyWithItsSwitchOn() {
        installGallery();
        assertNull("Off leaves the button to Messenger", Settings.chatGallery());
        assertEquals(0, Settings.lastActive(GalleryPicker.KEY));

        Settings.preferences.edit().putBoolean(GalleryPicker.KEY, true).commit();
        Bundle handed = Settings.chatGallery();
        assertNotNull(handed);
        assertTrue(Settings.lastActive(GalleryPicker.KEY) > 0);
        Intent marked = launch(handed);
        marked.putExtra(Settings.CHAT_CAMERA_MARK, true);
        Intent pick = Settings.chatCamera(marked);
        assertEquals(Intent.ACTION_PICK, pick.getAction());
        assertEquals("image/*", pick.getType());
        assertNull("The pick goes to whichever gallery app the person uses", pick.getComponent());
        assertEquals(Arrays.asList("image/*", "video/*"), Arrays.asList(pick.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)));
        assertEquals("The chat reads the pick back the way it reads a photo picked in another app", 1112, Settings.cameraRequestCode(pick, 7376));
        assertFalse(marked.hasExtra(Settings.CHAT_GALLERY_MARK));
        assertFalse(marked.hasExtra(Settings.CHAT_CAMERA_MARK));

        // The camera button's next launch is built fresh, without the gallery's extras, and stays Messenger's camera.
        Intent camera = launch(null);
        assertSame(camera, Settings.chatCamera(camera));
        assertEquals(7376, Settings.cameraRequestCode(camera, 7376));

        Settings.preferences.edit().putBoolean("paused", true).commit();
        assertNull("Pause brings Messenger's gallery back", Settings.chatGallery());
        assertTrue(Settings.hookErrors.isEmpty());
    }

    @Test public void withNoGalleryAppTheSwitchSaysWhyAndTheButtonStaysMessengers() {
        Settings.preferences.edit().putBoolean(GalleryPicker.KEY, true).commit();
        assertNull(GalleryPicker.pickIntent(app));
        assertFalse(Settings.available(GalleryPicker.KEY));
        assertNull(Settings.chatGallery());
        assertEquals(0, Settings.lastActive(GalleryPicker.KEY));

        // A gallery app removed between the tap and the launch leaves the launcher's own screen, marks off.
        Intent marked = launch(null).putExtra(Settings.CHAT_GALLERY_MARK, true);
        assertSame(marked, Settings.chatCamera(marked));
        assertFalse(marked.hasExtra(Settings.CHAT_GALLERY_MARK));
        assertEquals("thread", marked.getStringExtra("trigger2"));
        assertEquals(7376, Settings.cameraRequestCode(marked, 7376));

        installGallery();
        assertTrue(Settings.available(GalleryPicker.KEY));
        assertTrue(Settings.hookErrors.isEmpty());
    }

    /** A gallery app's provider, which knows each picked item's type. */
    public static class PickedProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return uri.getPath().startsWith("/video") ? "video/mp4" : "image/jpeg"; }
        @Override public Cursor query(Uri uri, String[] columns, String selection, String[] arguments, String order) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] arguments) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) { return 0; }
    }

    @Test public void aPickWithNoTypeGetsItsOwnTypeSoTheChatOpensItInTheEditor() {
        String authority = "com.example.gallery.picked";
        Robolectric.setupContentProvider(PickedProvider.class, authority);
        Uri photo = Uri.parse("content://" + authority + "/image/7");
        Intent off = new Intent().setData(photo);
        Settings.externalMediaResult(1112, Activity.RESULT_OK, off);
        assertNull("Off leaves the result as the gallery app sent it", off.getType());

        Settings.preferences.edit().putBoolean(GalleryPicker.KEY, true).commit();
        Intent picked = new Intent().setData(photo).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Settings.externalMediaResult(1112, Activity.RESULT_OK, picked);
        assertEquals("image/jpeg", picked.getType());
        assertEquals(photo, picked.getData());
        assertTrue("The read grant stays on the result", (picked.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        Intent video = new Intent().setData(Uri.parse("content://" + authority + "/video/8"));
        Settings.externalMediaResult(1112, Activity.RESULT_OK, video);
        assertEquals("video/mp4", video.getType());

        // A type the app sent, another request, a cancel and a file path stay as they are.
        Intent typed = new Intent().setDataAndType(photo, "image/png");
        Settings.externalMediaResult(1112, Activity.RESULT_OK, typed);
        assertEquals("image/png", typed.getType());
        Intent camera = new Intent().setData(photo);
        Settings.externalMediaResult(7376, Activity.RESULT_OK, camera);
        assertNull(camera.getType());
        Intent cancelled = new Intent().setData(photo);
        Settings.externalMediaResult(1112, Activity.RESULT_CANCELED, cancelled);
        assertNull(cancelled.getType());
        Intent file = new Intent().setData(Uri.parse("file:///sdcard/DCIM/a.jpg"));
        Settings.externalMediaResult(1112, Activity.RESULT_OK, file);
        assertNull(file.getType());
        Settings.externalMediaResult(1112, Activity.RESULT_OK, null);

        Settings.preferences.edit().putBoolean("paused", true).commit();
        Intent paused = new Intent().setData(photo);
        Settings.externalMediaResult(1112, Activity.RESULT_OK, paused);
        assertNull("Pause leaves it to Messenger", paused.getType());
        assertTrue(Settings.hookErrors.isEmpty());
    }

    @Test public void onlyThePickTheSwitchMadeGetsThePickedPhotoRequestCode() {
        Intent someoneElses = new Intent(Intent.ACTION_PICK).setType("image/*");
        assertFalse(GalleryPicker.isPick(someoneElses));
        assertEquals(7376, Settings.cameraRequestCode(someoneElses, 7376));
        assertFalse(GalleryPicker.isPick(null));
        assertEquals(7376, Settings.cameraRequestCode(null, 7376));
    }
}
