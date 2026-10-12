package app.hushmessenger.patches.controls

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextStyleTest {
    private fun Element.children(tag: String) = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }

    @Test fun theMenuEntriesStartDisabledAndOpenTheStyleScreen() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            "<manifest package=\"com.facebook.orca\"><application/></manifest>".byteInputStream())
        document.addTextStyle()
        val application = document.getElementsByTagName("application").item(0) as Element
        val entries = (0 until application.childNodes.length).map { application.childNodes.item(it) as Element }
        assertEquals(listOf("activity", "activity-alias", "activity-alias"), entries.map { it.tagName }, "An alias comes after its activity")
        assertEquals(TEXT_STYLE_ACTIVITY, entries[0].getAttribute("android:name"))
        assertEquals("false", entries[0].getAttribute("android:exported"))
        assertEquals(TEXT_STYLE_ENTRIES, entries.drop(1).map { it.getAttribute("android:name") to it.getAttribute("android:label") })
        for (alias in entries.drop(1)) {
            assertEquals(TEXT_STYLE_ACTIVITY, alias.getAttribute("android:targetActivity"))
            assertEquals("false", alias.getAttribute("android:enabled"), "Hidden until the extension sees the switch on")
            assertEquals("false", alias.getAttribute("android:exported"))
            val filter = alias.children("intent-filter").single()
            assertEquals(listOf("android.intent.action.PROCESS_TEXT"), filter.children("action").map { it.getAttribute("android:name") })
            assertEquals(listOf("android.intent.category.DEFAULT"), filter.children("category").map { it.getAttribute("android:name") })
            assertEquals(listOf("text/plain"), filter.children("data").map { it.getAttribute("android:mimeType") })
        }
    }

    @Test fun theExtensionKnowsTheEntriesByTheSameNames() {
        val root = File("..")
        val manifest = root.resolve("extensions/messenger/src/main/AndroidManifest.xml").readText()
        val source = root.resolve("extensions/messenger/src/main/java/app/hushmessenger/extension/TextStyleActivity.java").readText()
        assertTrue("android:name=\"$TEXT_STYLE_ACTIVITY\"" in manifest)
        for ((name, label) in TEXT_STYLE_ENTRIES) {
            assertTrue("android:name=\"$name\" android:targetActivity=\"$TEXT_STYLE_ACTIVITY\" android:label=\"$label\"" in manifest, name)
            assertTrue("\"$name\"" in source, name)
        }
        assertTrue("static final String KEY = \"$TEXT_STYLE\";" in source)
    }
}
