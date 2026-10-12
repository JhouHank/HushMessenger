package app.hushmessenger.extension;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class TextStyleTest {
    Application app;

    @Before public void reset() {
        app = RuntimeEnvironment.getApplication();
        Settings.initialize(app);
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        CrashGuard.resetForTests();
        TextStyleActivity.work = Runnable::run;
    }

    private static String points(int... points) { return new String(points, 0, points.length); }
    private static String bold(String text) { return TextStyleActivity.restyle(text, TextStyleActivity.BOLD_STYLE); }
    private static String italic(String text) { return TextStyleActivity.restyle(text, TextStyleActivity.ITALIC_STYLE); }

    private Intent selection(String entry, String text) {
        return new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setClassName(app.getPackageName(), entry)
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text);
    }

    private int state(String entry) {
        return app.getPackageManager().getComponentEnabledSetting(new ComponentName(app.getPackageName(), entry));
    }

    @Test public void lettersAndDigitsTakeTheStyleAndEverythingElseStays() {
        assertEquals(points(0x1D5D4, 0x1D607, 0x1D7EC, 0x1D7F5), bold("Az09"));
        assertEquals("Unicode has no italic digits", points(0x1D608, 0x1D63B) + "09", italic("Az09"));
        String hello = "Hi, привет 👋!";
        assertEquals(points(0x1D5DB, 0x1D5F6) + ", привет 👋!", bold(hello));
        assertEquals("Picking it again turns the letters back", hello, bold(bold(hello)));
        assertEquals("Hello world 2026", italic(italic("Hello world 2026")));
        for (String nothing : new String[] {"", "  !?", "ß", "привет", "👋"}) {
            assertNull(nothing, bold(nothing));
            assertNull(nothing, italic(nothing));
        }
        assertNull(italic("2026"));
    }

    @Test public void boldAndItalicCombineAndComeOffOneAtATime() {
        String both = points(0x1D63C, 0x1D657);
        assertEquals(both, italic(bold("Ab")));
        assertEquals(both, bold(italic("Ab")));
        assertEquals(points(0x1D608, 0x1D623), bold(both));
        assertEquals(points(0x1D5D4, 0x1D5EF), italic(both));
        assertEquals("A selection that's only partly bold turns all bold first", points(0x1D5D4, 0x1D5D5), bold("A" + points(0x1D5D5)));
        assertEquals("Bold digits stay bold under italic", points(0x1D656, 0x1D7ED), italic(bold("a1")));
    }

    @Test public void accentsRideAlongAndJoinBackWhenPlain() {
        assertEquals(points(0x1D5F2, 0x0301), bold("é"));
        String word = "ação Ñandú";
        assertEquals(word, bold(bold(word)));
        assertEquals(word, italic(italic(word)));
        assertEquals("Marks typed apart stay with their letter and join it once plain", "é", bold(bold("é")));
        assertNull("A lone mark is left alone", bold("́"));
    }

    @Test public void onlyAMenuEntryWithTheSwitchOnRestylesAnEditableSelection() {
        Intent boldEntry = selection(TextStyleActivity.BOLD, "Hi");
        assertNull("Off leaves the text alone", TextStyleActivity.styledFor(boldEntry));
        assertEquals(0, Settings.lastActive(TextStyleActivity.KEY));
        Settings.preferences.edit().putBoolean(TextStyleActivity.KEY, true).commit();
        assertEquals(points(0x1D5DB, 0x1D5F6), TextStyleActivity.styledFor(boldEntry));
        assertTrue(Settings.lastActive(TextStyleActivity.KEY) > 0);
        assertEquals(points(0x1D60F, 0x1D62A), TextStyleActivity.styledFor(selection(TextStyleActivity.ITALIC, "Hi")));
        assertNull(TextStyleActivity.styledFor(selection(TextStyleActivity.BOLD, "Hi").putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)));
        assertNull(TextStyleActivity.styledFor(selection(TextStyleActivity.class.getName(), "Hi")));
        assertNull(TextStyleActivity.styledFor(new Intent(Intent.ACTION_PROCESS_TEXT).setClassName(app.getPackageName(), TextStyleActivity.BOLD)));
        assertNull(TextStyleActivity.styledFor(null));
        Settings.preferences.edit().putBoolean("paused", true).commit();
        assertNull("Pause leaves the text alone", TextStyleActivity.styledFor(boldEntry));
    }

    @Test public void theScreenHandsTheStyledTextBackAndCloses() {
        Settings.preferences.edit().putBoolean(TextStyleActivity.KEY, true).commit();
        try (ActivityController<TextStyleActivity> controller =
                 Robolectric.buildActivity(TextStyleActivity.class, selection(TextStyleActivity.BOLD, "Hi")).create()) {
            ShadowActivity screen = shadowOf(controller.get());
            assertEquals(Activity.RESULT_OK, screen.getResultCode());
            assertEquals(points(0x1D5DB, 0x1D5F6), screen.getResultIntent().getStringExtra(Intent.EXTRA_PROCESS_TEXT));
            assertTrue(controller.get().isFinishing());
        }
        Settings.preferences.edit().putBoolean(TextStyleActivity.KEY, false).commit();
        try (ActivityController<TextStyleActivity> controller =
                 Robolectric.buildActivity(TextStyleActivity.class, selection(TextStyleActivity.BOLD, "Hi")).create()) {
            assertEquals(Activity.RESULT_CANCELED, shadowOf(controller.get()).getResultCode());
            assertTrue(controller.get().isFinishing());
        }
    }

    @Test public void theMenuEntriesFollowTheSwitchPauseAndSafeMode() {
        int off = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, on = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        assertTrue(TextStyleActivity.present(app));
        TextStyleActivity.watch();
        assertEquals(off, state(TextStyleActivity.BOLD));
        Settings.preferences.edit().putBoolean(TextStyleActivity.KEY, true).commit();
        assertEquals(on, state(TextStyleActivity.BOLD));
        assertEquals(on, state(TextStyleActivity.ITALIC));
        Settings.preferences.edit().putBoolean("paused", true).commit();
        assertEquals(off, state(TextStyleActivity.BOLD));
        assertEquals(off, state(TextStyleActivity.ITALIC));
        Settings.preferences.edit().putBoolean("paused", false).commit();
        assertEquals(on, state(TextStyleActivity.ITALIC));
        Settings.preferences.edit().putBoolean("analytics_uploads", true).commit();
        assertEquals("Other switches leave the entries alone", on, state(TextStyleActivity.BOLD));
        Settings.preferences.edit().putBoolean(TextStyleActivity.KEY, false).commit();
        assertEquals(off, state(TextStyleActivity.BOLD));

        Settings.preferences.edit().putBoolean(TextStyleActivity.KEY, true).putBoolean("safe_mode", true).commit();
        CrashGuard.resetForTests();
        CrashGuard.onProcessStart(app);
        TextStyleActivity.refresh();
        assertEquals("Safe mode takes the entries out", off, state(TextStyleActivity.BOLD));
        HostScreens.started = true;
        assertTrue(CrashGuard.clearSafeMode());
        assertEquals("Leaving safe mode brings them back", on, state(TextStyleActivity.BOLD));
    }

    @Test public void theSwitchSitsUnderConversationsAndStartsOff() {
        assertTrue(Settings.installed.contains(TextStyleActivity.KEY));
        String[] row = Arrays.stream(SettingsActivity.CONTROLS).filter(c -> c[0].equals(TextStyleActivity.KEY)).findFirst().orElseThrow();
        assertEquals("Bold and italic text", row[1]);
        assertEquals("conversations", row[3]);
        assertTrue(Settings.available(TextStyleActivity.KEY));
        assertFalse(Settings.preferences.contains(TextStyleActivity.KEY));
        assertFalse(Settings.wouldUse(TextStyleActivity.KEY));
    }
}
