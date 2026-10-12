package app.hushmessenger.extension;

import android.app.Dialog;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36}, qualifiers = "en-rUS-w390dp-h844dp-mdpi")
public class SettingsDialogTest {
    @Before public void reset() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.preferences.edit().clear().commit();
    }

    @After public void closeDialog() {
        Dialog dialog = ShadowDialog.getLatestDialog();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private Dialog open(SettingsActivity activity) {
        activity.getWindow().getDecorView().findViewWithTag("auto_reply_edit").performClick();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        return dialog;
    }

    @Test public void anIncompleteRuleStaysOpenUntilItsReplyIsWritten() {
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            Dialog dialog = open(screen.get());
            View root = dialog.getWindow().getDecorView();
            EditText words = root.findViewWithTag("auto_reply_when"), reply = root.findViewWithTag("auto_reply_reply");
            words.setText("hello");
            root.findViewWithTag("auto_reply_save").performClick();
            assertTrue(dialog.isShowing());
            assertTrue(AutoReply.rules(Settings.preferences).isEmpty());
            assertEquals(View.VISIBLE, root.findViewWithTag("auto_reply_error").getVisibility());
            assertTrue(reply.hasFocus());
            reply.setText("Hi there");
            assertEquals(View.GONE, root.findViewWithTag("auto_reply_error").getVisibility());
            root.findViewWithTag("auto_reply_save").performClick();
            assertFalse(dialog.isShowing());
            assertEquals("Hi there", AutoReply.rules(Settings.preferences).get(0).reply);
        }
    }

    @Test public void unfinishedRepliesAndSelectionSurviveRecreationWithoutSaving() {
        Bundle state = new Bundle();
        Dialog old;
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            old = open(screen.get());
            View root = old.getWindow().getDecorView();
            ((EditText) root.findViewWithTag("auto_reply_when")).setText(" hello, hours ");
            EditText reply = root.findViewWithTag("auto_reply_reply");
            reply.setText("Draft with trailing space ");
            reply.requestFocus();
            reply.setSelection(3, 8);
            ((CheckBox) root.findViewWithTag("auto_reply_groups")).setChecked(true);
            screen.get().onSaveInstanceState(state);
        }
        assertFalse(old.isShowing());
        assertTrue(AutoReply.rules(Settings.preferences).isEmpty());
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).create(state).start().resume().visible()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Dialog restored = ShadowDialog.getLatestDialog();
            assertTrue(restored.isShowing());
            View root = restored.getWindow().getDecorView();
            EditText words = root.findViewWithTag("auto_reply_when"), reply = root.findViewWithTag("auto_reply_reply");
            assertEquals(" hello, hours ", words.getText().toString());
            assertEquals("Draft with trailing space ", reply.getText().toString());
            assertEquals(3, reply.getSelectionStart());
            assertEquals(8, reply.getSelectionEnd());
            assertTrue(reply.hasFocus());
            assertTrue(((CheckBox) root.findViewWithTag("auto_reply_groups")).isChecked());
            assertTrue(AutoReply.rules(Settings.preferences).isEmpty());
        }
    }

    @Test public void cancelLeavesPreviouslySavedRepliesAlone() {
        AutoReply.save(Settings.preferences, List.of(new AutoReply.Rule("hello", "Saved reply")), false);
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            Dialog dialog = open(screen.get());
            View root = dialog.getWindow().getDecorView();
            ((EditText) root.findViewWithTag("auto_reply_reply")).setText("Discard this");
            root.findViewWithTag("auto_reply_cancel").performClick();
            assertFalse(dialog.isShowing());
            assertEquals("Saved reply", AutoReply.rules(Settings.preferences).get(0).reply);
        }
    }

    @Test public void theRuleLimitDisablesAddingAndRemovalRestoresIt() {
        List<AutoReply.Rule> rules = new ArrayList<>();
        for (int i = 0; i < AutoReply.MAX_RULES; i++) rules.add(new AutoReply.Rule("word" + i, "Reply " + i));
        AutoReply.save(Settings.preferences, rules, false);
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            View root = open(screen.get()).getWindow().getDecorView();
            Button add = root.findViewWithTag("auto_reply_add");
            assertFalse(add.isEnabled());
            root.findViewWithTag("auto_reply_remove").performClick();
            assertTrue(add.isEnabled());
            add.performClick();
            assertFalse(add.isEnabled());
        }
    }

    @Test public void shortLargeTextSheetsScrollTheirActionsIntoReach() {
        RuntimeEnvironment.setFontScale(2f);
        RuntimeEnvironment.setQualifiers("en-rUS-w320dp-h300dp-mdpi");
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            View root = open(screen.get()).getWindow().getDecorView();
            View sheet = root.findViewWithTag("settings_dialog");
            sheet.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY));
            sheet.layout(0, 0, 320, 300);
            ScrollView scroll = root.findViewWithTag("dialog_scroll");
            View actions = root.findViewWithTag("dialog_actions");
            assertSame(scroll.getChildAt(0), actions.getParent());
            assertEquals(LinearLayout.VERTICAL, ((LinearLayout) actions).getOrientation());
            scroll.fullScroll(View.FOCUS_DOWN);
            assertTrue(scroll.getScrollY() > 0);
            assertTrue(root.findViewWithTag("auto_reply_save").getWidth() <= 280);
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsets insets = new WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 16))
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 180)).build();
                sheet.dispatchApplyWindowInsets(insets);
                assertEquals(196, sheet.getPaddingBottom());
                assertEquals(44, sheet.getPaddingTop());
            }
        }
    }

    @Test public void rtlSheetsSetBothLayoutAndTextDirection() {
        RuntimeEnvironment.setQualifiers("ar-rXB-w390dp-h844dp-mdpi");
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            View sheet = open(screen.get()).getWindow().getDecorView().findViewWithTag("settings_dialog");
            assertEquals(View.LAYOUT_DIRECTION_RTL, sheet.getLayoutDirection());
            assertEquals(View.TEXT_DIRECTION_RTL, sheet.getTextDirection());
        }
    }
}
