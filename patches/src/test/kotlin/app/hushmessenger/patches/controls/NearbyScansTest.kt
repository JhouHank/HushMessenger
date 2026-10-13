package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal const val SYNTHETIC_NEARBY_SCAN_SCHEDULER =
    "$FOREGROUND_LOCATION->A04(Landroid/content/Context;$FB_USER_SESSION${FOREGROUND_LOCATION}J)V"
private const val LOOP = "LX/Yxd;"
private const val BLUETOOTH_TASK = "LX/Yoi;"
private val STATIC = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value

/** The scheduler as 582 writes it: under the controller's lock it builds the loop's task, names it and queues it. */
private val SCHEDULER_BODY = """
    monitor-enter p2
    const-string v2, "$NEARBY_SCAN_MARK"
    new-instance v1, $LOOP
    invoke-direct {v1, p0, p1, p2}, $LOOP-><init>(Landroid/content/Context;$FB_USER_SESSION$FOREGROUND_LOCATION)V
    invoke-static {v1, v2}, LX/0zx;->A01(Ljava/lang/Runnable;Ljava/lang/String;)Ljava/lang/Runnable;
    monitor-exit p2
    return-void
""".trimIndent()

/** The loop's task cut down to its three jobs: a location request, a Wi-Fi scan and the Bluetooth scan operation. */
private val LOOP_BODY = """
    const/4 v0, 0x0
    invoke-virtual {v0}, $WIFI_MANAGER->startScan()Z
    invoke-virtual {v0}, $BLE_OPERATION->A04()V
    return-void
""".trimIndent()

private val BLUETOOTH_BODY = """
    const/4 v0, 0x0
    invoke-virtual {v0, v0, v0, v0}, $BLE_SCANNER->startScan(Ljava/util/List;Landroid/bluetooth/le/ScanSettings;Landroid/bluetooth/le/ScanCallback;)V
    return-void
""".trimIndent()

private fun scheduler(body: String = SCHEDULER_BODY, id: String = SYNTHETIC_NEARBY_SCAN_SCHEDULER, registers: Int = 9, flags: Int = STATIC) =
    fixtureMethod(id, body, registers, flags)

private fun task(type: String, body: String) = fixtureMethod("$type->run()V", body)

private fun bluetoothTask(type: String = BLUETOOTH_TASK, body: String = BLUETOOTH_BODY, operation: String = BLE_OPERATION) =
    fixtureClass(type, listOf(task(type, body)), interfaces = listOf("Ljava/lang/Runnable;"),
        extraFields = listOf(ImmutableField(type, "A00", operation, AccessFlags.PUBLIC.value, null, null, null)))

/** The controller with its scheduler, the task it queues, and the Bluetooth scan operation's own task. */
internal fun nearbyScansFixture(
    schedulerMethod: MutableMethod? = scheduler(),
    loopBody: String = LOOP_BODY,
    bluetooth: List<MutableClass> = listOf(bluetoothTask()),
): List<MutableClass> = listOf(
    fixtureClass(FOREGROUND_LOCATION, listOfNotNull(schedulerMethod)),
    fixtureClass(LOOP, listOf(task(LOOP, loopBody)), interfaces = listOf("Ljava/lang/Runnable;")),
) + bluetooth

private fun Method.code() = implementation!!.instructions.toList()

/** An odd-length insertion can add or drop the nop that aligns a switch table further down, so nops don't count. */
private fun List<Instruction>.opcodes() = map { it.opcode }.filter { it != Opcode.NOP }

class NearbyScansTest {
    @AfterTest fun reset() {
        activeProfile = SYNTHETIC_PROFILE
    }

    private fun found(classes: List<MutableClass>) = findControls(classes).getValue(NEARBY_SCANS).map { it.hookId() }.toSet()

    @Test fun theLoopsSchedulerIsFoundWhenBothScansSitBehindIt() {
        assertEquals(setOf(SYNTHETIC_NEARBY_SCAN_SCHEDULER), found(nearbyScansFixture()))
        validateControls(findControls(nearbyScansFixture()), setOf(NEARBY_SCANS))
    }

    @Test fun everyProfileHooksTheSameScheduler() {
        for (profile in listOf(SYNTHETIC_PROFILE, BASE_PROFILE, PROFILE_346415706)) {
            assertEquals(setOf(SYNTHETIC_NEARBY_SCAN_SCHEDULER), profile.hooks.getValue(NEARBY_SCANS))
            activeProfile = profile
            validateControls(findControls(nearbyScansFixture()), setOf(NEARBY_SCANS))
        }
    }

    // The switch says it stops Messenger's scans. A build that scans from anywhere the scheduler doesn't reach would
    // make that untrue, so it gets no hook at all.
    @Test fun aScanOutsideTheLoopOrAMissingSchedulerLeavesTheControlUnapplied() {
        val strayWifi = fixtureClass("LX/Zzz;", listOf(task("LX/Zzz;", LOOP_BODY.replace("invoke-virtual {v0}, $BLE_OPERATION->A04()V\n", ""))))
        val cases = mapOf(
            "no scheduler" to nearbyScansFixture(schedulerMethod = null),
            "a scheduler that doesn't name its task" to nearbyScansFixture(scheduler(SCHEDULER_BODY.replace(NEARBY_SCAN_MARK, "other"))),
            "an instance scheduler" to nearbyScansFixture(scheduler(flags = AccessFlags.PUBLIC.value, registers = 10)),
            "a scheduler that queues another task" to nearbyScansFixture(scheduler(SCHEDULER_BODY.replace(LOOP, "LX/Yxe;"))),
            "two schedulers" to nearbyScansFixture().let { classes ->
                listOf(fixtureClass(FOREGROUND_LOCATION, listOf(scheduler(), scheduler(id = SYNTHETIC_NEARBY_SCAN_SCHEDULER.replace("->A04(", "->A0P(")))) ) + classes.drop(1)
            },
            "a second Wi-Fi scan elsewhere" to nearbyScansFixture() + strayWifi,
            "a second Bluetooth scan elsewhere" to nearbyScansFixture(bluetooth = listOf(bluetoothTask(), bluetoothTask("LX/Yoj;"))),
            "no Wi-Fi scan in the queued task" to nearbyScansFixture(loopBody = LOOP_BODY.replace("invoke-virtual {v0}, $WIFI_MANAGER->startScan()Z\n", "")),
            "no Bluetooth scan" to nearbyScansFixture(bluetooth = emptyList()),
            "a Bluetooth scan the queued task doesn't drive" to nearbyScansFixture(loopBody = LOOP_BODY.replace("invoke-virtual {v0}, $BLE_OPERATION->A04()V\n", "")),
            "a Bluetooth scan outside the scan operation" to nearbyScansFixture(bluetooth = listOf(bluetoothTask(operation = "LX/Other;"))),
        )
        for ((case, classes) in cases) {
            assertTrue(found(classes).isEmpty(), case)
            assertFailsWith<PatchException>(case) { validateControls(findControls(classes), setOf(NEARBY_SCANS)) }
        }
    }

    @Test fun theSchedulerAsksTheSwitchBeforeItTakesItsLockOrQueuesAnything() {
        val method = scheduler()
        val before = method.code()
        injectControl(NEARBY_SCANS, mapOf(NEARBY_SCANS to listOf(method)))
        val code = method.code()
        assertEquals(Opcode.INVOKE_STATIC, code[0].opcode)
        assertEquals("$SETTINGS->stopNearbyScans()Z", (code[0] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals(Opcode.IF_EQZ, code[2].opcode)
        assertEquals(4, code.branchTarget(2))
        assertEquals(Opcode.RETURN_VOID, code[3].opcode)
        // Off, Pause and safe mode fall through to Messenger's own scheduler, lock included, unchanged.
        assertEquals(Opcode.MONITOR_ENTER, code[4].opcode)
        assertEquals(before, code.drop(4))
    }

    @Test fun anUnusableMethodRefusesTheSwitchBeforeAnyEdit() {
        val cases = mapOf(
            // Five registers are exactly the parameter words, so v0 would be the context.
            "no free register" to scheduler(SCHEDULER_BODY.replace("v2", "p0").replace("v1", "p1"), registers = 5),
            "a jump back to the start" to scheduler(SCHEDULER_BODY.replace("monitor-enter p2", ":again\nmonitor-enter p2")
                .replace("monitor-exit p2", "if-eqz v1, :again\nmonitor-exit p2")),
            "another method" to fixtureMethod("$FOREGROUND_LOCATION->A03(Landroid/content/Context;$FB_USER_SESSION$FOREGROUND_LOCATION)V",
                "return-void", registers = 5, flags = STATIC),
        )
        for ((case, bad) in cases) {
            val badBefore = bad.code()
            assertFailsWith<PatchException>(case) { injectControl(NEARBY_SCANS, mapOf(NEARBY_SCANS to listOf(bad))) }
            assertEquals(badBefore, bad.code(), case)
        }
    }

    @Test fun everySupportedBuildKeepsItsScansBehindTheOneScheduler() {
        val root = System.getenv("HUSH_NATIVE_FIXTURES")
        assumeTrue(root != null, "Set HUSH_NATIVE_FIXTURES to the exact stock fixture directory")
        val apks = Files.list(Path.of(root!!)).use { it.filter { p -> p.toString().endsWith(".apk") }.sorted().toList() }
        assertEquals(controlProfiles.size, apks.size)
        for (apk in apks) {
            val code = apk.fileName.toString().substringBeforeLast(".apk").substringAfterLast('-')
            activeProfile = controlProfileFor(code)
            val dex = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.forApi(35))
            val hooks = findNearbyScans(dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes })
            assertEquals(activeProfile.hooks.getValue(NEARBY_SCANS), hooks.map { it.hookId() }.toSet(), code)
            validateControls(mapOf(NEARBY_SCANS to hooks), setOf(NEARBY_SCANS))
            val method = MutableMethod(hooks.single())
            val before = method.code()
            method.injectNearbyScans()
            assertEquals("$SETTINGS->stopNearbyScans()Z", (method.code()[0] as ReferenceInstruction).reference.toString(), code)
            assertEquals(Opcode.RETURN_VOID, method.code()[3].opcode, code)
            assertEquals(before.opcodes(), method.code().drop(4).opcodes(), code)
        }
    }
}
