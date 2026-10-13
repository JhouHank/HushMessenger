package app.hushmessenger.extension;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class SupportRowTest {
    @Before public void reset() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.preferences.edit().clear().commit();
    }

    /** Activity hands every launch to this hidden method, and a launch Android refuses throws SecurityException from it. */
    public static final class DenyingInstrumentation extends Instrumentation {
        public Instrumentation.ActivityResult execStartActivity(Context who, IBinder contextThread, IBinder token,
                Activity target, Intent intent, int requestCode, Bundle options) {
            throw new SecurityException("Permission Denial: private launch detail");
        }
    }

    /** Makes [activity]'s launches fail the way a refused one does, until the returned undo runs. */
    static Runnable denyLaunches(Activity activity) throws Exception {
        Field field = Activity.class.getDeclaredField("mInstrumentation");
        field.setAccessible(true);
        Object original = field.get(activity);
        field.set(activity, new DenyingInstrumentation());
        return () -> {
            try {
                field.set(activity, original);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        };
    }

    @Test public void theSupportRowOpensKoFiInTheBrowser() {
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            root.findViewWithTag("tab_app").performClick();
            View support = root.findViewWithTag("support");
            assertEquals("Support HushMessenger. Buy me a coffee on Ko-fi", support.getContentDescription().toString());
            assertTrue(support.isFocusable());
            support.performClick();
            Intent opened = Shadows.shadowOf(screen.get()).getNextStartedActivity();
            assertEquals(Intent.ACTION_VIEW, opened.getAction());
            assertEquals("https://ko-fi.com/X8K126YVER", opened.getDataString());
            assertTrue(opened.hasCategory(Intent.CATEGORY_BROWSABLE));
        }
    }

    @Test public void withoutABrowserTheRowSaysSo() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            root.findViewWithTag("tab_app").performClick();
            root.findViewWithTag("support").performClick();
            assertNull(Shadows.shadowOf(screen.get()).getNextStartedActivity());
            assertEquals("No web browser found on this phone", ShadowToast.getTextOfLatestToast());
        }
    }

    /** A launch Android refuses leaves settings open with a short note, and nothing about the page in the log. */
    @Test public void aRefusedPageLaunchKeepsSettingsOpenAndSaysSo() throws Exception {
        ShadowLog.clear();
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            root.findViewWithTag("tab_app").performClick();
            Runnable undo = denyLaunches(screen.get());
            try {
                for (String tag : new String[] {"source_licenses", "support"}) {
                    ShadowToast.reset();
                    root.findViewWithTag(tag).performClick();
                    assertEquals(tag, "Your phone didn't allow opening that page", ShadowToast.getTextOfLatestToast());
                }
            } finally {
                undo.run();
            }
            assertFalse(screen.get().isFinishing());
            // The same row works once launches are allowed again.
            root.findViewWithTag("support").performClick();
            assertEquals("https://ko-fi.com/X8K126YVER", Shadows.shadowOf(screen.get()).getNextStartedActivity().getDataString());
        }
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag("HushMessenger")) {
            assertFalse(item.msg, item.msg.contains("http") || item.msg.contains("private launch detail"));
            assertFalse(item.throwable != null && String.valueOf(item.throwable.getMessage()).contains("private launch detail"));
        }
    }
}
