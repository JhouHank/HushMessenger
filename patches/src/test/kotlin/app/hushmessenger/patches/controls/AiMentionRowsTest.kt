package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ROW_CHECKS = "LX/1yQ;"
private const val ROW_PARAMS = "$FB_USER_SESSION$THREAD_KEY_TYPE$CAPABILITIES"
internal const val SYNTHETIC_META_AI_ROW = "$ROW_CHECKS->A0Q()Z"
internal const val SYNTHETIC_IMAGINE_ROW = "$ROW_CHECKS->A0y(${ROW_PARAMS}ZZ)Z"
internal const val SYNTHETIC_IMAGINE_YOURSELF_ROW = "$ROW_CHECKS->A0w($ROW_PARAMS)Z"

/** One server flag read the way 582 writes it, answered into v0. */
private fun flagRead(flag: Long) = """
    invoke-static {}, LX/19a;->A0B()LX/5dD;
    move-result-object v2
    const-wide v0, ${flag}L
    invoke-static {v2, v0, v1}, LX/17Q;->A1Y(Ljava/lang/Object;J)Z
    move-result v0
""".trimIndent()

/** The Meta AI row check: either of its two flags turns the row on. */
private fun metaAiRow(id: String = SYNTHETIC_META_AI_ROW, flags: List<Long> = META_AI_ROW_FLAGS.toList()) = fixtureMethod(id, """
    const/4 v3, 0x0
    ${flagRead(flags[0])}
    if-nez v0, :on
    ${flags.getOrNull(1)?.let { flagRead(it) } ?: "const/4 v0, 0x0"}
    if-eqz v0, :off
    :on
    const/4 v3, 0x1
    :off
    return v3
""".trimIndent(), registers = 5)

/** An imagine check: the row shows while its flag is on. */
private fun imagineRow(id: String, flag: Long, flags: Int = AccessFlags.PUBLIC.value) = fixtureMethod(id, """
    const/4 v4, 0x0
    ${flagRead(flag)}
    if-eqz v0, :off
    const/4 v4, 0x1
    :off
    return v4
""".trimIndent(), registers = 11, flags = flags)

private fun rowChecks(type: String = ROW_CHECKS) = listOf(
    metaAiRow(SYNTHETIC_META_AI_ROW.replace(ROW_CHECKS, type)),
    imagineRow(SYNTHETIC_IMAGINE_ROW.replace(ROW_CHECKS, type), IMAGINE_ROW_FLAG),
    imagineRow(SYNTHETIC_IMAGINE_YOURSELF_ROW.replace(ROW_CHECKS, type), IMAGINE_YOURSELF_ROW_FLAG),
)

/** The three checks for the catalog-wide discovery fixture. */
internal fun aiMentionRowsFixture() = listOf(fixtureClass(ROW_CHECKS, rowChecks()))

private fun Method.code() = implementation!!.instructions.toList()

/** The switch prologue answers false ahead of the stock check, which stays as it was. */
private fun assertSwitched(before: List<Instruction>, after: List<Instruction>, label: String) {
    assertEquals(before, after.drop(5), label)
    assertEquals("$SETTINGS->hideMetaAi()Z", (after[0] as ReferenceInstruction).reference.toString(), label)
    assertEquals(listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN), after.subList(1, 5).map { it.opcode }, label)
}

class AiMentionRowsTest {
    @AfterTest fun reset() { activeProfile = SYNTHETIC_PROFILE }

    @Test fun eachRowCheckAnswersFalseWhileTheSwitchIsOn() {
        val checks = rowChecks()
        val found = findControls(listOf(fixtureClass(ROW_CHECKS, checks)))
        assertEquals(setOf(SYNTHETIC_META_AI_ROW, SYNTHETIC_IMAGINE_ROW, SYNTHETIC_IMAGINE_YOURSELF_ROW),
            found.getValue(AI_MENTION_ROWS).map { it.hookId() }.toSet())
        validateControls(found, setOf(AI_MENTION_ROWS))
        val before = checks.map { it.code() }
        injectControl(AI_MENTION_ROWS, mapOf(AI_MENTION_ROWS to checks))
        checks.zip(before).forEach { (method, code) -> assertSwitched(code, method.code(), method.hookId()) }
    }

    @Test fun aCheckThatMovedOrRepeatsLeavesTheRowsUnhooked() {
        val (meta, imagine, yourself) = rowChecks()
        // Each check is told apart by its parameters and the flag it reads.
        val moved = mapOf(
            "Meta AI check reads one of its two flags" to metaAiRow(flags = META_AI_ROW_FLAGS.take(1)),
            "imagine reads another flag" to imagineRow(SYNTHETIC_IMAGINE_ROW, IMAGINE_YOURSELF_ROW_FLAG),
            "imagine yourself takes the imagine parameters" to imagineRow(SYNTHETIC_IMAGINE_ROW.replace("A0y", "A0z"), IMAGINE_YOURSELF_ROW_FLAG),
            "static check" to imagineRow(SYNTHETIC_IMAGINE_ROW, IMAGINE_ROW_FLAG, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value),
        )
        for ((case, method) in moved) assertNull(method.aiMentionRow(), case)
        // All three or none: a missing check, a second look-alike, or checks split across classes.
        assertTrue(findAiMentionRows(listOf(fixtureClass(ROW_CHECKS, listOf(meta, imagine)))).isEmpty())
        assertTrue(findAiMentionRows(aiMentionRowsFixture() + fixtureClass("LX/1yR;", rowChecks("LX/1yR;").take(1))).isEmpty())
        assertTrue(findAiMentionRows(listOf(fixtureClass(ROW_CHECKS, listOf(meta, imagine)),
            fixtureClass("LX/1yR;", listOf(rowChecks("LX/1yR;")[2])))).isEmpty())
        assertEquals(3, findAiMentionRows(listOf(fixtureClass(ROW_CHECKS, listOf(meta, imagine, yourself)))).size)
    }

    @Test fun anUnexpectedMethodStopsTheControlBeforeAnyEdit() {
        val checks = rowChecks()
        val stranger = imagineRow(SYNTHETIC_IMAGINE_ROW.replace("A0y", "A0z"), 0x81063b00000001L)
        val before = checks.map { it.code() }
        val failure = assertFailsWith<PatchException> { injectControl(AI_MENTION_ROWS, mapOf(AI_MENTION_ROWS to checks + stranger)) }
        assertContains(failure.message.orEmpty(), "Meta AI checks moved")
        assertEquals(before, checks.map { it.code() })
    }

    @Test fun everySupportedBuildHooksItsThreeRowChecks() {
        val root = System.getenv("HUSH_NATIVE_FIXTURES")
        assumeTrue(root != null, "Set HUSH_NATIVE_FIXTURES to the exact stock fixture directory")
        val apks = Files.list(Path.of(root!!)).use { it.filter { p -> p.toString().endsWith(".apk") }.sorted().toList() }
        assertEquals(controlProfiles.size, apks.size)
        for (apk in apks) {
            val code = apk.fileName.toString().substringBeforeLast(".apk").substringAfterLast('-')
            activeProfile = controlProfileFor(code)
            val dex = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.forApi(35))
            val checks = findAiMentionRows(dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes })
            assertEquals(activeProfile.hooks.getValue(AI_MENTION_ROWS), checks.map { it.hookId() }.toSet(), code)
            validateControls(mapOf(AI_MENTION_ROWS to checks), setOf(AI_MENTION_ROWS))
            for (check in checks) {
                val method = MutableMethod(check)
                val before = method.code()
                method.injectAiMentionRows()
                assertSwitched(before, method.code(), "$code ${check.hookId()}")
            }
        }
    }
}
