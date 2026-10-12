package app.hushmessenger.extension;

import android.app.Dialog;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Switch;
import android.widget.TextView;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Optional native renders of production settings. Set HUSH_SCREENSHOTS to the PNG output directory. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "en-rUS-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsScreenshotTest {
    private static final int WIDTH = 390, HEIGHT = 844;
    private Path output;
    private PackageInfo installedPackage;
    private Bundle originalMetadata;
    private boolean originalStarted, originalFailed;

    @Before public void prepareProductionFixture() throws Exception {
        String directory = System.getenv("HUSH_SCREENSHOTS");
        assumeTrue("Set HUSH_SCREENSHOTS to export native settings renders", directory != null && !directory.trim().isEmpty());
        output = Paths.get(directory).toAbsolutePath();
        Files.createDirectories(output);
        originalStarted = HostScreens.started;
        originalFailed = HostScreens.failed;
        var app = RuntimeEnvironment.getApplication();
        installedPackage = app.getPackageManager().getPackageInfo(app.getPackageName(),
            PackageManager.GET_META_DATA | PackageManager.GET_PROVIDERS | PackageManager.GET_ACTIVITIES);
        originalMetadata = installedPackage.applicationInfo.metaData;
        Bundle metadata = originalMetadata == null ? new Bundle() : new Bundle(originalMetadata);
        metadata.remove("hush.preview");
        metadata.putBoolean("hush.native_bubble_routes", true);
        metadata.putBoolean("hush.feature.menu_row", true);
        for (String[] spec : SettingsActivity.CONTROLS) metadata.putBoolean("hush.feature." + spec[0], true);
        installedPackage.applicationInfo.metaData = metadata;
        Shadows.shadowOf(app.getPackageManager()).installPackage(installedPackage);
        Shadows.shadowOf(app.getPackageManager()).addActivityIfNotPresent(
            new android.content.ComponentName(app.getPackageName(), SettingsActivity.DRAWER_ALIAS));
        Settings.initialize(app);
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        Settings.hookErrors.clear();
        CrashGuard.resetForTests();
        HostScreens.started = true;
        HostScreens.failed = false;
    }

    @After public void restoreFixtureMetadata() {
        if (installedPackage == null) return;
        Dialog dialog = ShadowDialog.getLatestDialog();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        installedPackage.applicationInfo.metaData = originalMetadata;
        var app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app.getPackageManager()).installPackage(installedPackage);
        Settings.preferences.edit().clear().commit();
        Settings.initialize(app);
        HostScreens.started = originalStarted;
        HostScreens.failed = originalFailed;
    }

    @Test public void captureProductionPagesAndDialogsInBothThemes() throws Exception {
        for (boolean light : new boolean[] {false, true}) {
            Settings.preferences.edit().clear().putBoolean("light", light).commit();
            String theme = light ? "light" : "dark";
            try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
                View root = screen.get().getWindow().getDecorView();
                layout(root);
                assertFalse(Settings.preview);
                assertNull(root.findViewWithTag("preview_notice"));
                assertTrue(required(root, "controls_page").isShown());
                visibleTarget(required(root, "open_messenger"));
                visibleTarget(required(root, "find_control"));
                for (String[] spec : SettingsActivity.CONTROLS) {
                    Switch choice = root.findViewWithTag(spec[0]);
                    assertNotNull("Installed control: " + spec[0], choice);
                    assertTrue(spec[0] + " height", choice.getHeight() >= 48);
                    assertTrue(spec[0] + " width", choice.getWidth() >= 48);
                    View row = (View) choice.getParent();
                    assertTrue(spec[0] + " fits its row", choice.getLeft() >= 0 && choice.getRight() <= row.getWidth());
                }
                write(root, "settings-controls-" + theme);

                required(root, "tab_app").performClick();
                layout(root);
                assertTrue(required(root, "app_page").isShown());
                assertFalse(required(root, "controls_page").isShown());
                visibleTarget(required(root, "tab_app"));
                write(root, "settings-app-" + theme);

                required(root, "tab_controls").performClick();
                layout(root);
                captureDialog(root, "message_log_view", "message_log_empty", "settings-message-log-" + theme);
                captureDialog(root, "auto_reply_edit", null, "settings-auto-reply-" + theme);

                View restart = RestartActivity.content(new SettingsUi(screen.get(), light));
                screen.get().setContentView(restart);
                screen.visible();
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS);
                layout(restart);
                TextView status = (TextView) required(restart, "restart_status");
                assertEquals(new SettingsText(screen.get()).get("restarting"), status.getText().toString());
                assertTrue("Restart message laid out", status.getWidth() > 0 && status.getHeight() > 0);
                write(restart, "settings-restart-" + theme);
            }
        }
    }

    private void captureDialog(View page, String action, String loadedText, String name) throws Exception {
        View button = required(page, action);
        button.requestRectangleOnScreen(new Rect(0, 0, button.getWidth(), button.getHeight()), true);
        layout(page);
        visibleTarget(button);
        assertTrue(button.performClick());
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull("Dialog opened by " + action, dialog);
        assertTrue(dialog.isShowing());
        try {
            View root = dialog.getWindow().getDecorView();
            if (loadedText != null) {
                String expected = new SettingsText(dialog.getContext()).get(loadedText);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!containsText(root, expected) && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue("Log finished loading", containsText(root, expected));
            }
            layoutDialog(dialog);
            if ("auto_reply_edit".equals(action)) {
                visibleTarget(required(root, "auto_reply_save"));
                visibleTarget(required(root, "auto_reply_groups"));
                assertTrue(required(root, "auto_reply_reply").getHeight() >= 48);
            }
            write(root, name);
        } finally {
            dialog.dismiss();
            layout(page);
        }
    }

    private static void layout(View root) {
        for (int pass = 0; pass < 2; pass++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, WIDTH, HEIGHT);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private static void layoutDialog(Dialog dialog) {
        View root = dialog.getWindow().getDecorView();
        var attributes = dialog.getWindow().getAttributes();
        for (int pass = 0; pass < 2; pass++) {
            root.measure(windowSpec(attributes.width, WIDTH), windowSpec(attributes.height, HEIGHT));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private static int windowSpec(int requested, int available) {
        return View.MeasureSpec.makeMeasureSpec(requested > 0 ? Math.min(requested, available) : available,
            requested == WindowManager.LayoutParams.WRAP_CONTENT ? View.MeasureSpec.AT_MOST : View.MeasureSpec.EXACTLY);
    }

    private static View required(View root, String tag) {
        View view = root.findViewWithTag(tag);
        assertNotNull("View: " + tag, view);
        return view;
    }

    private static void visibleTarget(View view) {
        Rect visible = new Rect();
        assertTrue("Visible: " + view.getTag(), view.isShown() && view.getGlobalVisibleRect(visible));
        assertTrue("Usable visible height: " + view.getTag(), visible.height() >= 48);
        assertTrue("Usable visible width: " + view.getTag(), visible.width() >= 48);
    }

    private static boolean containsText(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (containsText(((ViewGroup) view).getChildAt(i), expected)) return true;
        return false;
    }

    private void write(View root, String name) throws Exception {
        assertTrue("Rendered area", root.getWidth() > 0 && root.getHeight() > 0);
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        try (OutputStream stream = Files.newOutputStream(output.resolve(name + ".png"))) {
            root.draw(new Canvas(bitmap));
            assertTrue("PNG export", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally {
            bitmap.recycle();
        }
    }
}
