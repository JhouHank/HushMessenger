package app.hushmessenger.extension;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class XheAudioTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Before public void reset() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        CrashGuard.resetForTests();
    }

    private static ByteBuffer config(int... bytes) {
        ByteBuffer buffer = ByteBuffer.allocate(bytes.length);
        for (int b : bytes) buffer.put((byte) b);
        buffer.flip();
        return buffer;
    }

    /** Runs the hook the way Messenger does, on its background copy thread. */
    private static void fromCopyThread(File file) throws InterruptedException {
        Thread copy = new Thread(() -> XheAudio.fixSharedFile(file));
        copy.start();
        copy.join(10_000);
        assertFalse(copy.isAlive());
    }

    @Test public void theObjectTypeIsReadFromTheAudioConfig() {
        assertEquals("AAC-LC", 2, XheAudio.objectType(config(0x12, 0x10)));
        assertEquals("HE-AAC", 5, XheAudio.objectType(config(0x2B, 0x92)));
        // 31 escapes to six more bits: 11111 001|010 is 32 + 10.
        assertEquals("xHE-AAC", XheAudio.XHE_OBJECT_TYPE, XheAudio.objectType(config(0xF9, 0x40)));
        assertEquals(-1, XheAudio.objectType(config(0xF9)));
        assertEquals(-1, XheAudio.objectType(config()));
        assertEquals(-1, XheAudio.objectType(null));
        ByteBuffer shared = config(0xF9, 0x40);
        XheAudio.objectType(shared);
        assertEquals("the format's own buffer isn't moved", 0, shared.position());
    }

    @Test public void aSoundTrackStartingBelowZeroIsKeptAndBothTracksMoveTogether() {
        // ffmpeg's AAC encoder delay: the sound's first sample sits at -23219 us, the picture's at 0.
        assertEquals(23_219L, XheAudio.startShift(true, 0L, true, -23_219L));
        assertEquals(23_219L, XheAudio.startShift(false, -1L, true, -23_219L));
        assertEquals(0L, XheAudio.startShift(true, 0L, true, 0L));
        assertEquals("a finished track's -1 isn't a start", 0L, XheAudio.startShift(true, 0L, false, -1L));
        assertFalse("the negative sound sample goes first", XheAudio.takePicture(true, 0L, true, -23_219L));
        assertFalse("the sound isn't over just because its time is negative", XheAudio.takePicture(false, -1L, true, -23_219L));
        assertTrue(XheAudio.takePicture(true, 0L, true, 0L));
        assertTrue(XheAudio.takePicture(true, 40_000L, false, -1L));
    }

    @Test public void aCopyThatCantBeReadStaysAsItWasAndLeavesNothingBehind() throws Exception {
        File copy = folder.newFile("orca_share_media123.mp4");
        byte[] bytes = "not a video".getBytes();
        Files.write(copy.toPath(), bytes);
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        fromCopyThread(copy);
        assertTrue("the switch was asked", Settings.lastActive(XheAudio.KEY) > 0);
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        assertArrayEquals(bytes, Files.readAllBytes(copy.toPath()));
        assertEquals(Arrays.asList("orca_share_media123.mp4"), Arrays.asList(folder.getRoot().list()));
    }

    @Test public void offPauseAndTheMainThreadLeaveTheCopyAlone() throws Exception {
        File copy = folder.newFile("orca_share_media7.mp4");
        byte[] bytes = "not a video".getBytes();
        Files.write(copy.toPath(), bytes);
        fromCopyThread(copy);
        assertEquals("off", 0, Settings.lastActive(XheAudio.KEY));
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).putBoolean("paused", true).commit();
        fromCopyThread(copy);
        assertEquals("paused", 0, Settings.lastActive(XheAudio.KEY));
        Settings.preferences.edit().putBoolean("paused", false).commit();
        // Robolectric runs the test on the main thread, where a conversion could freeze Messenger.
        XheAudio.fixSharedFile(copy);
        assertEquals("main thread", 0, Settings.lastActive(XheAudio.KEY));
        XheAudio.fixSharedFile(null);
        assertArrayEquals(bytes, Files.readAllBytes(copy.toPath()));
    }

    @Test public void onlyMp4CopiesAreConsidered() throws Exception {
        for (String name : new String[] {"orca_share_media1.mov", "orca_share_media2.3gp", "orca_share_media3.webm", "orca_share_media4"}) {
            File copy = folder.newFile(name);
            Files.write(copy.toPath(), "not a video".getBytes());
            assertFalse(name, XheAudio.fix(copy));
        }
    }

    /** Runs the transcoder hook the way Messenger does, off the main thread, and answers what it gave back. */
    private static String fromTranscoder(String source) throws InterruptedException {
        String[] answer = new String[1];
        Thread transcoder = new Thread(() -> answer[0] = XheAudio.transcodeSource(source));
        transcoder.start();
        transcoder.join(10_000);
        assertFalse(transcoder.isAlive());
        return answer[0];
    }

    @Test public void aPickedVideoThatDoesntNeedItComesBackAsItWas() throws Exception {
        File picked = folder.newFile("VID_20261014.mp4");
        byte[] bytes = "not a video".getBytes();
        Files.write(picked.toPath(), bytes);
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        for (String source : new String[] {picked.getPath(), "file://" + picked.getPath(),
            "content://media/external/video/media/1", "https://example.com/v.mp4", "not a path", ""}) {
            assertEquals(source, fromTranscoder(source));
        }
        assertNull(fromTranscoder(null));
        assertTrue("the switch was asked", Settings.lastActive(XheAudio.KEY) > 0);
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        assertArrayEquals("the picked file is never touched", bytes, Files.readAllBytes(picked.toPath()));
        File cache = new File(RuntimeEnvironment.getApplication().getCacheDir(), XheAudio.PICKED);
        assertFalse("nothing was converted, so no cache folder", cache.exists());
    }

    @Test public void offPauseAndTheMainThreadGiveTheSourceBack() throws Exception {
        File picked = folder.newFile("VID_1.mp4");
        assertEquals(picked.getPath(), fromTranscoder(picked.getPath()));
        assertEquals("off", 0, Settings.lastActive(XheAudio.KEY));
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).putBoolean("paused", true).commit();
        assertEquals(picked.getPath(), fromTranscoder(picked.getPath()));
        assertEquals("paused", 0, Settings.lastActive(XheAudio.KEY));
        Settings.preferences.edit().putBoolean("paused", false).commit();
        assertEquals(picked.getPath(), XheAudio.transcodeSource(picked.getPath()));
        assertEquals("main thread", 0, Settings.lastActive(XheAudio.KEY));
    }

    /** One box: a 32-bit size, the type and [body]. */
    private static byte[] box(String type, byte[] body) {
        ByteBuffer box = ByteBuffer.allocate(8 + body.length);
        box.putInt(8 + body.length).put(type.getBytes(StandardCharsets.US_ASCII)).put(body);
        return box.array();
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static final byte[] FILE_TYPE = box("ftyp", "isom\0\0\2\0isommp42".getBytes(StandardCharsets.US_ASCII));

    /** Shaped like the #58 recording: ftyp, the picture data, moov with Samsung's udta records, then sefd at the end. */
    private static byte[] samsungRecording() {
        return samsungRecording(0);
    }

    /** [samsungRecording] with its picture data filled with [fill], so two recordings differ only there. */
    private static byte[] samsungRecording(int fill) {
        byte[] picture = new byte[64];
        Arrays.fill(picture, (byte) fill);
        return join(FILE_TYPE, box("mdat", picture),
            box("moov", join(box("mvhd", new byte[100]), box("udta", box("smta", new byte[12])))),
            box("sefd", "\0\0\u0080\0Samsung_Capture_Info\0Screen recording".getBytes(StandardCharsets.ISO_8859_1)));
    }

    /** What [samsungRecording] looks like once its sefd is renamed. */
    private static byte[] renamed(byte[] recording) {
        return renamedAt(recording, recording.length - 8 - 41);
    }

    /** The bytes of the file a transcoder answer names, a path or a file Uri. */
    private static byte[] sentBytes(String sent) throws Exception {
        String path = sent;
        if (sent.startsWith("file:")) {
            // SDK 28 on a Windows host builds file://C%3A%5C... from the backslash path, with the path in the authority.
            path = Uri.parse(sent).getPath();
            if (path == null || path.isEmpty()) path = Uri.decode(sent.substring("file://".length()));
        }
        File file = new File(path);
        assertTrue(sent, file.isFile());
        return Files.readAllBytes(file.toPath());
    }

    private static File pickedCache() {
        return new File(RuntimeEnvironment.getApplication().getCacheDir(), XheAudio.PICKED);
    }

    /** Every file the cache holds is a finished copy, named by its bytes, with nothing half-made left beside it. */
    private static void onlyFinishedCopies(int count) {
        String[] names = pickedCache().list();
        assertNotNull(names);
        assertEquals(Arrays.toString(names), count, names.length);
        for (String name : names) assertTrue(name, name.matches("[0-9a-f]{64}\\.mp4"));
    }

    /** The cache name convertPicked used before 2026-10-18: a 32-bit hash of the Uri, size and time. */
    private static String oldKey(File file) {
        return Integer.toHexString((Uri.fromFile(file) + ":" + file.length() + ":" + file.lastModified()).hashCode());
    }

    @Test public void twoVideosWhoseOldCacheNamesMatchEachSendTheirOwn() throws Exception {
        File movies = folder.newFolder("Movies");
        File first = new File(movies, "Aa.mp4");
        File second = new File(movies, "BB.mp4");
        byte[] a = samsungRecording(1);
        byte[] b = samsungRecording(2);
        Files.write(first.toPath(), a);
        Files.write(second.toPath(), b);
        assertTrue(first.setLastModified(1_792_108_800_000L));
        assertTrue(second.setLastModified(1_792_108_800_000L));
        // "Aa" and "BB" hash alike in Java, so the same size and time gave both one cache name.
        assertEquals("the old names collide", oldKey(first), oldKey(second));
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        String sentA = fromTranscoder(first.toURI().getPath());
        String sentB = fromTranscoder(second.toURI().getPath());
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        assertNotEquals(sentA, sentB);
        assertArrayEquals("Aa sends Aa", renamed(a), sentBytes(sentA));
        assertArrayEquals("BB sends BB", renamed(b), sentBytes(sentB));
        assertArrayEquals(a, Files.readAllBytes(first.toPath()));
        assertArrayEquals(b, Files.readAllBytes(second.toPath()));
        onlyFinishedCopies(2);
    }

    @Test public void aVideoChangedUnderTheSameNameSizeAndTimeIsConvertedAgain() throws Exception {
        File picked = new File(folder.newFolder("DCIM"), "VID_20261018.mp4");
        byte[] before = samsungRecording(3);
        byte[] after = samsungRecording(4);
        Files.write(picked.toPath(), before);
        assertTrue(picked.setLastModified(1_792_108_800_000L));
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        String path = picked.toURI().getPath();
        String first = fromTranscoder(path);
        Files.write(picked.toPath(), after);
        assertTrue(picked.setLastModified(1_792_108_800_000L));
        String second = fromTranscoder(path);
        assertNotEquals("new bytes, new copy", first, second);
        assertArrayEquals(renamed(after), sentBytes(second));
        assertArrayEquals("the first copy is still whole for a send that's using it", renamed(before), sentBytes(first));
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
    }

    static final String VIDEOS = "app.hushmessenger.test.videos";

    /** Serves one file at any Uri, the way a gallery hands a picked video over. */
    public static final class Videos extends ContentProvider {
        static volatile File served;

        @Override public boolean onCreate() { return true; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(served, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public String getType(Uri uri) { return "video/mp4"; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }

    @Test public void aContentUriWhoseVideoChangedIsConvertedAgain() throws Exception {
        Videos.served = folder.newFile("gallery.mp4");
        byte[] before = samsungRecording(5);
        byte[] after = samsungRecording(6);
        Files.write(Videos.served.toPath(), before);
        Robolectric.setupContentProvider(Videos.class, VIDEOS);
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        String uri = "content://" + VIDEOS + "/video/7";
        String first = fromTranscoder(uri);
        assertTrue(first, first.startsWith("file:"));
        assertArrayEquals(renamed(before), sentBytes(first));
        // The provider now hands over other bytes for the same Uri. A new file stands in for an edit in place, since
        // the SDK 28 runtime keeps the first one open and Windows won't let it be written then.
        Videos.served = folder.newFile("gallery-edited.mp4");
        Files.write(Videos.served.toPath(), after);
        String second = fromTranscoder(uri);
        assertNotEquals("the same Uri with new bytes gets a new copy", first, second);
        assertArrayEquals(renamed(after), sentBytes(second));
        assertEquals("the same bytes again reuse their copy", second, fromTranscoder(uri));
        assertArrayEquals(after, Files.readAllBytes(Videos.served.toPath()));
        onlyFinishedCopies(2);
    }

    @Test public void conversionsRunningAtOnceEachKeepTheirOwnFiles() throws Exception {
        File movies = folder.newFolder("Movies");
        File[] picked = {new File(movies, "Aa.mp4"), new File(movies, "BB.mp4")};
        byte[][] bytes = {samsungRecording(7), samsungRecording(8)};
        for (int i = 0; i < 2; i++) {
            Files.write(picked[i].toPath(), bytes[i]);
            assertTrue(picked[i].setLastModified(1_792_108_800_000L));
        }
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        int sends = 8;
        String[] sent = new String[sends];
        CountDownLatch go = new CountDownLatch(1);
        Thread[] threads = new Thread[sends];
        for (int i = 0; i < sends; i++) {
            int send = i;
            threads[i] = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException stop) {
                    return;
                }
                sent[send] = XheAudio.transcodeSource(picked[send % 2].toURI().getPath());
            });
            threads[i].start();
        }
        go.countDown();
        for (Thread thread : threads) {
            thread.join(10_000);
            assertFalse(thread.isAlive());
        }
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        for (int i = 0; i < sends; i++) assertArrayEquals("send " + i, renamed(bytes[i % 2]), sentBytes(sent[i]));
        for (int i = 0; i < 2; i++) assertArrayEquals(bytes[i], Files.readAllBytes(picked[i].toPath()));
        onlyFinishedCopies(2);
    }

    /** [bytes] with the box type at [offset] swapped for free. */
    private static byte[] renamedAt(byte[] bytes, int offset) {
        byte[] renamed = bytes.clone();
        System.arraycopy("free".getBytes(StandardCharsets.US_ASCII), 0, renamed, offset + 4, 4);
        return renamed;
    }

    @Test public void aSamsungRecordingsShareCopyHasItsTagRenamedInPlace() throws Exception {
        File copy = folder.newFile("orca_share_media42.mp4");
        byte[] bytes = samsungRecording();
        Files.write(copy.toPath(), bytes);
        int sefd = bytes.length - box("sefd", "\0\0\u0080\0Samsung_Capture_Info\0Screen recording".getBytes(StandardCharsets.ISO_8859_1)).length;
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        fromCopyThread(copy);
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        assertArrayEquals("only the top-level sefd's type changes, so every offset stays", renamedAt(bytes, sefd), Files.readAllBytes(copy.toPath()));
        assertEquals(Arrays.asList("orca_share_media42.mp4"), Arrays.asList(folder.getRoot().list()));
        assertFalse("nothing is left to rename", XheAudio.dropSamsungBoxes(copy));
    }

    @Test public void onlyTopLevelSamsungBoxesInAnMp4AreRenamed() throws Exception {
        byte[] nested = join(FILE_TYPE, box("moov", box("udta", box("sefd", new byte[8]))), box("mdat", new byte[16]));
        byte[] notMp4 = join(box("mdat", new byte[16]), box("sefd", new byte[8]));
        // A 64-bit sized mdat before the tag, as long recordings write it.
        ByteBuffer large = ByteBuffer.allocate(16 + 32);
        large.putInt(1).put("mdat".getBytes(StandardCharsets.US_ASCII)).putLong(16 + 32);
        byte[] wide = join(FILE_TYPE, large.array(), box("sefd", new byte[8]));
        // A box that claims more than the file holds ends the walk, so the sefd after it is never reached.
        ByteBuffer broken = ByteBuffer.allocate(8);
        broken.putInt(1 << 20).put("mdat".getBytes(StandardCharsets.US_ASCII));
        byte[] cut = join(FILE_TYPE, broken.array(), box("sefd", new byte[8]));
        byte[] tiny = join(FILE_TYPE, new byte[] {0, 0, 0, 4, 's', 'e', 'f', 'd'});
        for (byte[] bytes : new byte[][] {nested, notMp4, cut, tiny}) {
            File file = folder.newFile();
            Files.write(file.toPath(), bytes);
            assertFalse(XheAudio.hasSamsungBox(file));
            assertFalse(XheAudio.dropSamsungBoxes(file));
            assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
        }
        File file = folder.newFile();
        Files.write(file.toPath(), wide);
        assertTrue(XheAudio.dropSamsungBoxes(file));
        assertArrayEquals(renamedAt(wide, FILE_TYPE.length + 48), Files.readAllBytes(file.toPath()));
        assertFalse("a missing file has none", XheAudio.hasSamsungBox(new File(folder.getRoot(), "gone.mp4")));
    }

    @Test public void aPickedSamsungRecordingIsSentAsARenamedCopy() throws Exception {
        File picked = folder.newFile("Screen_Recording_20261014.mp4");
        byte[] bytes = samsungRecording();
        Files.write(picked.toPath(), bytes);
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        // A path that starts with a slash, as Messenger passes it on the phone (here /C:/... on Windows).
        String path = picked.toURI().getPath();
        String sent = fromTranscoder(path);
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        File cache = new File(RuntimeEnvironment.getApplication().getCacheDir(), XheAudio.PICKED);
        assertEquals(cache.getCanonicalPath(), new File(sent).getParentFile().getCanonicalPath());
        assertArrayEquals(renamedAt(bytes, bytes.length - 8 - 41), Files.readAllBytes(new File(sent).toPath()));
        assertArrayEquals("the picked file is never touched", bytes, Files.readAllBytes(picked.toPath()));
        assertEquals("the same video reuses its copy", sent, fromTranscoder(path));
        assertEquals("only the finished copy is kept", 1, cache.list().length);
        assertTrue("a file Uri comes back as one", fromTranscoder("file://" + path).startsWith("file:"));
    }

    @Test public void convertedCopiesOlderThanADayAreCleared() throws Exception {
        File cache = folder.newFolder(XheAudio.PICKED);
        long now = System.currentTimeMillis();
        File stale = new File(cache, "1a2b.mp4");
        File fresh = new File(cache, "3c4d.mp4");
        Files.write(stale.toPath(), new byte[] {1});
        Files.write(fresh.toPath(), new byte[] {1});
        assertTrue(stale.setLastModified(now - XheAudio.KEEP_MS - 60_000));
        assertTrue(fresh.setLastModified(now - 60_000));
        XheAudio.prune(cache, now);
        assertEquals(Arrays.asList("3c4d.mp4"), Arrays.asList(cache.list()));
    }

    @Test public void theSwitchSitsUnderConversationsAndStartsOff() {
        assertTrue(Settings.installed.contains(XheAudio.KEY));
        String[] row = Arrays.stream(SettingsActivity.CONTROLS).filter(c -> c[0].equals(XheAudio.KEY)).findFirst().orElseThrow();
        assertEquals("Fix videos that won't send", row[1]);
        assertEquals("conversations", row[3]);
        assertFalse(Settings.preferences.contains(XheAudio.KEY));
        assertFalse(Settings.wouldUse(XheAudio.KEY));
    }
}
