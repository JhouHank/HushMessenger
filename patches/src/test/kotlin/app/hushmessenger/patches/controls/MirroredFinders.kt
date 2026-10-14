package app.hushmessenger.patches.controls

import app.hushmessenger.patches.CompatMirror
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A real APK's classes. CompatReport already runs on the six 583 APKs themselves, so the fixtures this size skip the
 * second finder and its minute of extra work.
 */
private const val MIRROR_LIMIT = 5_000

/**
 * The hooks controlPatch proves with validateHook. The other controls (anonymous stories, menu settings, text style,
 * the community inbox and the rest) prove theirs on their own path, so they keep only the one-way check.
 */
private val PROVED_BY_VALIDATE_HOOK: Set<String> by lazy { controlHooks.filterKeys { it != COMMUNITY_INBOX }.values.flatten().toSet() }

/**
 * Hooks of [PROVED_BY_VALIDATE_HOOK] whose proof CompatReport's findControls doesn't repeat, and why. None of them can
 * get a build recorded, since --save runs the real patch first.
 */
private val PROVED_ONLY_BY_THE_PATCH: Map<String, String> =
    (listOf("ads", "bubble_mode", "browser", "subtabs", "people_jewel") + pluginGates.keys)
        .associateWith { "CompatReport proves the body through a profile field, and FIELD_CONTROLS fails the control when it changes" } +
    listOf(HIDE_READ_RECEIPTS, REPLY_READ).associateWith { "the patch proves the account's identity flow, CompatReport the dispatcher's outline" } +
    (ORIGINAL_VIDEO to "CompatReport doesn't count the passthrough size checks") +
    listOf(MESSAGE_LOG, AUTO_REPLY).associateWith { "CompatReport checks two of the contract's six fields, the id and the hidden flag" }

/**
 * findControls, checked against CompatReport's findControls on the same classes. CompatReport must never resolve a
 * method the patch's discovery doesn't, or a build it passes would be refused at patch time. And for the hooks of
 * [PROVED_BY_VALIDATE_HOOK] outside [PROVED_ONLY_BY_THE_PATCH], it resolves a key exactly when the patch finds the key
 * and proves every method of it.
 */
internal fun mirroredControls(classes: Iterable<ClassDef>): Map<String, List<Method>> =
    mirrored(classes, findControls(classes), emptySet())

/** The same, with a community inbox contract handed in. CompatReport always finds its own, so that key isn't compared. */
internal fun mirroredControls(classes: Iterable<ClassDef>, community: CommunityInboxContract?): Map<String, List<Method>> =
    mirrored(classes, findControls(classes, community), setOf(COMMUNITY_INBOX))

private fun mirrored(classes: Iterable<ClassDef>, found: Map<String, List<Method>>, skip: Set<String>): Map<String, List<Method>> {
    val list = classes.toList()
    if (list.size <= MIRROR_LIMIT) {
        val ours = CompatMirror.ids(found)
        val compat = CompatMirror.controls(list).filterKeys { it !in skip }
        val extra = compat.mapNotNull { (key, ids) -> (ids - ours[key].orEmpty().toSet()).takeIf { it.isNotEmpty() }?.let { "$key $it" } }
        assertTrue(extra.isEmpty(), "CompatReport resolves what findControls doesn't: $extra")
        val differ = (found.keys + compat.keys).filter { it in PROVED_BY_VALIDATE_HOOK && it !in skip && it !in PROVED_ONLY_BY_THE_PATCH }
            .sorted().mapNotNull { key ->
            val methods = found[key].orEmpty()
            val expected = if (methods.isNotEmpty() && methods.all { proves(key, it, list) }) ours.getValue(key) else emptyList()
            "$key: patch $expected, CompatReport ${compat[key].orEmpty()}".takeIf { expected.sorted() != compat[key].orEmpty().sorted() }
        }
        assertTrue(differ.isEmpty(), "CompatReport and the patch's proof disagree: $differ")
    }
    return found
}

/** What the patch proves about [hook]'s [method] before its first edit: validateHook, then what controlPatch resolves first. */
private fun proves(hook: String, method: Method, classes: List<ClassDef>): Boolean = runCatching {
    val classOf = { type: String -> classes.firstOrNull { it.type == type } }
    // Some fixtures hand over plain dexlib2 methods. validateHook edits nothing, so a wrapper reads the same.
    (method as? MutableMethod ?: MutableMethod(method)).validateHook(hook)
    when (hook) {
        INBOX_REFRESH_HOOK -> resolveInboxRefresh(method, classOf)
        "emoji_typeface" -> method.emojiFontHolder(classOf)
    }
}.isSuccess

/** findNativeBubbleRoutes, which CompatReport's nativeBubbleRoutes has to answer exactly. */
internal fun mirroredBubbleRoutes(classes: List<ClassDef>, gate: String): String? {
    val found = findNativeBubbleRoutes(classes, gate)
    assertEquals(found, CompatMirror.nativeBubbleRoutes(classes, gate), "CompatReport's bubble routes differ")
    return found
}
