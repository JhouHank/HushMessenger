package app.hushmessenger.extension;

import android.content.Context;
import com.facebook.messaging.threadsettings.plugins.threadviewentrypoint.implementation.ThreadSettingsButtonImplementation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class MarkReadButtonTest {
    private final Object session = new Object();
    private final ThreadKey thread = new ThreadKey("chat");

    @Before public void reset() {
        Context context = RuntimeEnvironment.getApplication();
        Settings.initialize(context);
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        CrashGuard.resetForTests();
        CrashGuard.onProcessStart(context);
        Settings.lastReply = null;
        synchronized (MarkReadButton.held) { MarkReadButton.held.clear(); }
        SeenHandler.sent.clear();
    }

    private void on(boolean hide, boolean button) {
        Settings.preferences.edit().putBoolean("hide_read_receipts", hide).putBoolean(MarkReadButton.KEY, button).commit();
    }

    private static String lastToast() {
        ShadowLooper.idleMainLooper();
        return ShadowToast.getTextOfLatestToast();
    }

    @Test public void aTapSendsTheHeldSeenOnceThroughMessengersOwnHandler() {
        on(true, true);
        SeenHandler handler = new SeenHandler(thread);
        SeenHandler.open(session, handler, "message-1");
        SeenHandler.open(session, handler, "message-2");
        assertTrue("opening the chat sends nothing", SeenHandler.sent.isEmpty());

        // A reopened chat has a new key object that compares equal, as Messenger's ThreadKey does.
        Object tag = MarkReadButton.tag(session, new ThreadKey("chat"));
        assertTrue(MarkReadButton.clicked(tag, null));
        assertEquals(Arrays.asList("message-2"), SeenHandler.sent);
        assertEquals("Marked as read", lastToast());

        assertTrue(MarkReadButton.clicked(tag, null));
        assertEquals("a second tap has nothing left to send", Arrays.asList("message-2"), SeenHandler.sent);
        assertEquals("Nothing new to mark as read", lastToast());

        // The replay ends with the tap, so the next Seen is held back again.
        SeenHandler.open(session, handler, "message-3");
        assertEquals(Arrays.asList("message-2"), SeenHandler.sent);
    }

    @Test public void nothingIsHeldWhileTheButtonIsOffOrHideReadReceiptsIsPaused() {
        on(true, false);
        SeenHandler handler = new SeenHandler(thread);
        SeenHandler.open(session, handler, "message-1");
        assertTrue(SeenHandler.sent.isEmpty());
        assertTrue(MarkReadButton.held.isEmpty());
        assertFalse(MarkReadButton.shown());

        on(true, true);
        Settings.preferences.edit().putBoolean("paused", true).commit();
        SeenHandler.open(session, handler, "message-2");
        assertEquals("paused, Messenger sends Seen itself", Arrays.asList("message-2"), SeenHandler.sent);
        assertTrue(MarkReadButton.held.isEmpty());
        assertFalse(MarkReadButton.shown());
    }

    @Test public void anotherAccountsTagOrAnotherChatSendsNothing() {
        on(true, true);
        SeenHandler.open(session, new SeenHandler(thread), "message-1");
        assertTrue(MarkReadButton.clicked(MarkReadButton.tag(new Object(), thread), null));
        assertTrue(MarkReadButton.clicked(MarkReadButton.tag(session, new ThreadKey("another-chat")), null));
        assertTrue(SeenHandler.sent.isEmpty());
        assertEquals("Nothing new to mark as read", lastToast());
    }

    @Test public void messengersOwnButtonsGoOnAsBefore() {
        assertFalse(MarkReadButton.clicked(null, null));
        assertFalse(MarkReadButton.clicked(new Object(), null));
        assertFalse(MarkReadButton.clicked("thread_view_title_bar_qr_scanner_button", null));
        assertNull(MarkReadButton.tag(null, thread));
        assertNull(MarkReadButton.tag(session, null));
    }

    @Test public void theButtonTakesTheDetailsButtonsPlaceInAFullBar() throws Exception {
        Object voice = new Button(new Listener(new Object())), video = new Button(new Listener(new Object()));
        Object details = new Button(new Listener(new ThreadSettingsButtonImplementation()));
        Object ours = new Object();
        List<Object> full = new ArrayList<>(Arrays.asList(voice, video, details, null));
        MarkReadButton.place(full, ours);
        assertEquals(Arrays.asList(voice, video, ours, null), full);

        List<Object> roomy = new ArrayList<>(Arrays.asList(voice, details, null));
        MarkReadButton.place(roomy, ours);
        assertEquals("with room, it goes in ahead of details", Arrays.asList(voice, ours, details, null), roomy);
    }

    @Test public void withoutADetailsButtonItOnlyTakesAFreeSlot() throws Exception {
        Object a = new Button(new Listener(new Object())), b = new Button(new Listener(new Object())), c = new Button(new Listener(new Object()));
        Object ours = new Object();
        List<Object> full = new ArrayList<>(Arrays.asList(a, b, c, null));
        MarkReadButton.place(full, ours);
        assertEquals("no call button is pushed out", Arrays.asList(a, b, c, null), full);

        List<Object> roomy = new ArrayList<>(Arrays.asList(a, null, null));
        MarkReadButton.place(roomy, ours);
        assertEquals(Arrays.asList(a, null, null, ours), roomy);

        List<Object> untouched = new ArrayList<>(Arrays.asList(a, null));
        MarkReadButton.place(untouched, null);
        assertEquals(Arrays.asList(a, null), untouched);
    }

    @Test public void theBarIsLeftAloneWhenTheButtonIsOff() {
        on(true, false);
        List<Object> buttons = new ArrayList<>(Arrays.asList(new Button(new Listener(new ThreadSettingsButtonImplementation()))));
        MarkReadButton.add(buttons, new Object());
        assertEquals(1, buttons.size());
        MarkReadButton.add(null, new Object());
    }

    /** Messenger's read handler as the patch leaves it: Hide read receipts asks first, then the stock job runs. */
    static final class SeenHandler {
        static final List<String> sent = new ArrayList<>();
        final Object thread;

        SeenHandler(Object thread) { this.thread = thread; }

        static void open(Object session, SeenHandler handler, String messageId) {
            if (Settings.hideChatSeen(session, handler.thread, handler, messageId)) return;
            sent.add(messageId);
        }
    }

    /** A top bar button and its tap listener, shaped like Messenger's: the listener holds the plugin it calls. */
    static final class Button {
        final Listener listener;

        Button(Listener listener) { this.listener = listener; }
    }

    static final class Listener {
        final Object plugin;

        Listener(Object plugin) { this.plugin = plugin; }
    }

    private static final class ThreadKey {
        private final String id;

        ThreadKey(String id) { this.id = id; }

        @Override public boolean equals(Object other) {
            return other instanceof ThreadKey && id.equals(((ThreadKey) other).id);
        }

        @Override public int hashCode() { return id.hashCode(); }
    }
}
