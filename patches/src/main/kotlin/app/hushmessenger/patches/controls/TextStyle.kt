package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import org.w3c.dom.Document
import org.w3c.dom.Element

internal const val TEXT_STYLE = "text_style"
internal const val TEXT_STYLE_ACTIVITY = "app.hushmessenger.extension.TextStyleActivity"
/** The selection menu's two entries and their labels. The extension tells them apart by these names. */
internal val TEXT_STYLE_ENTRIES = listOf(
    "app.hushmessenger.extension.BoldText" to "Bold",
    "app.hushmessenger.extension.ItalicText" to "Italic",
)

/**
 * Declares the screen behind Bold and Italic and its two selection menu entries (#42). Android lists every enabled
 * PROCESS_TEXT activity of an app in that app's own text fields, exported or not, so the entries start disabled and the
 * extension enables them only while the switch is in effect. Nothing in Messenger's code changes.
 */
internal fun Document.addTextStyle() {
    val application = getElementsByTagName("application").item(0) as? Element
        ?: throw PatchException("Messenger controls: expected one application")
    fun Element.child(tag: String, vararg attributes: Pair<String, String>): Element = createElement(tag).also { node ->
        attributes.forEach { (name, value) -> node.setAttribute("android:$name", value) }
        appendChild(node)
    }
    application.child("activity", "name" to TEXT_STYLE_ACTIVITY, "exported" to "false", "excludeFromRecents" to "true",
        "theme" to "@android:style/Theme.Translucent.NoTitleBar")
    // An alias has to come after the activity it points at.
    for ((name, label) in TEXT_STYLE_ENTRIES) {
        val filter = application.child("activity-alias", "name" to name, "targetActivity" to TEXT_STYLE_ACTIVITY,
            "label" to label, "exported" to "false", "enabled" to "false").child("intent-filter")
        filter.child("action", "name" to "android.intent.action.PROCESS_TEXT")
        filter.child("category", "name" to "android.intent.category.DEFAULT")
        filter.child("data", "mimeType" to "text/plain")
    }
}
