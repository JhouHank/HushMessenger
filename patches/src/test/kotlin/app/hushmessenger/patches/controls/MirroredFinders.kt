package app.hushmessenger.patches.controls

import app.hushmessenger.patches.CompatMirror
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
 * findControls, checked against CompatReport's findControls on the same classes: CompatReport must never resolve a
 * method the patch's discovery doesn't, or a build it passes would be refused at patch time. The other way round is
 * expected, since CompatReport proves at discovery what the patch checks just before its edits.
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
        val extra = CompatMirror.controls(list).filterKeys { it !in skip }
            .mapNotNull { (key, ids) -> (ids - ours[key].orEmpty().toSet()).takeIf { it.isNotEmpty() }?.let { "$key $it" } }
        assertTrue(extra.isEmpty(), "CompatReport resolves what findControls doesn't: $extra")
    }
    return found
}

/** findNativeBubbleRoutes, which CompatReport's nativeBubbleRoutes has to answer exactly. */
internal fun mirroredBubbleRoutes(classes: List<ClassDef>, gate: String): String? {
    val found = findNativeBubbleRoutes(classes, gate)
    assertEquals(found, CompatMirror.nativeBubbleRoutes(classes, gate), "CompatReport's bubble routes differ")
    return found
}
