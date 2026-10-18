package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.w3c.dom.Element
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal const val GALLERY_HANDLER = "LX/6CC;->BpW(LX/5IZ;Ljava/lang/String;)V"
private const val GALLERY_EDITOR = "LX/5JN;->A0C(${EDIT_GALLERY_ITEM}LX/0c8;)V"
private const val GALLERY_PROVIDER = "LX/6CC;->A0L:LX/0c8;"
private const val COMPOSER_RESULT = "LX/7Rm;->onActivityResult(IILandroid/content/Intent;)V"

/** The chat composer's event handler, cut down to its Edit and gallery branches. Parameters start at v9. */
private val GALLERY_HANDLER_BODY = """
    move-object/from16 v0, p0
    const-string v1, "com.facebook.xapp.messaging.composer.gallery.event.EditGalleryItemEvent"
    invoke-virtual {p2, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :gallery
    check-cast p1, $EDIT_GALLERY_ITEM
    iget-object v1, v0, $GALLERY_PROVIDER
    invoke-static {p1, v1}, $GALLERY_EDITOR
    return-void
    :gallery
    const-string v1, "com.facebook.xapp.messaging.composer.gallery.event.ActivateGalleryEvent"
    invoke-virtual {p2, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :other
    check-cast p1, $ACTIVATE_GALLERY
    iget-object v4, v0, LX/6CC;->A07:LX/5WP;
    const-class v1, LX/5gW;
    invoke-static {v4, p1, v1}, LX/5JN;->A01(LX/5WP;${ACTIVATE_GALLERY}Ljava/lang/Class;)V
    return-void
    :other
    return-void
""".trimIndent()

/** Edit's static helper: it takes the chat camera's launcher from the provider and starts Messenger's editor with it. */
private fun galleryEditorBody(start: String = "LX/6iH;->BxF(Landroid/os/Bundle;)Z") = """
    invoke-interface {p1}, LX/0c8;->get()Ljava/lang/Object;
    move-result-object v0
    check-cast v0, LX/6iH;
    invoke-static {}, LX/17v;->A0E()Landroid/os/Bundle;
    move-result-object v1
    invoke-interface {v0, v1}, $start
    return-void
""".trimIndent()

private val STUB_START = "const/4 v0, 0x0\nreturn v0"
private val STATIC = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
private val INTERFACE = AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value

private fun handlerClass(body: String = GALLERY_HANDLER_BODY, registers: Int = 12) = fixtureClass("LX/6CC;",
    listOf(fixtureMethod(GALLERY_HANDLER, body, registers = registers)),
    extraFields = listOf("A0L" to "LX/0c8;", "A07" to "LX/5WP;").map { (name, type) ->
        ImmutableField("LX/6CC;", name, type, AccessFlags.FINAL.value, null, null, null)
    }, flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value)

/** The handler, Edit's helper and the provider interface. The chat camera's launcher comes from [systemCameraFixture]. */
internal fun externalGalleryFixture(): List<MutableClass> = listOf(
    handlerClass(),
    fixtureClass("LX/5JN;", listOf(fixtureMethod(GALLERY_EDITOR, galleryEditorBody(), registers = 4, flags = STATIC))),
    fixtureClass("LX/0c8;", flags = INTERFACE),
)

private fun Method.code() = implementation!!.instructions.toList()

/** Switch payloads sit at even addresses, so the nop padding one comes or goes as code is added above it. */
private fun List<Instruction>.unpadded() = filter { it.opcode != Opcode.NOP }

private val FINAL_CLASS = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

/** Messenger 583's shape: the launcher abstract, one final subclass that runs its start and one that brings its own. */
private fun abstractLauncher(): Array<MutableClass> {
    val launcher = systemCameraFixture().single { it.type == "LX/5xZ;" }
    return arrayOf(
        MutableClass(ImmutableClassDef(launcher.type, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, launcher.superclass,
            launcher.interfaces, null, emptySet(), launcher.fields, launcher.methods)),
        fixtureClass("LX/5xY;", superclass = "LX/5xZ;", flags = FINAL_CLASS),
        fixtureClass("LX/5xX;", listOf(fixtureMethod(CHAT_CAMERA_START.replace("LX/5xZ;", "LX/5xX;"), STUB_START, registers = 2)),
            superclass = "LX/5xZ;", flags = FINAL_CLASS),
    )
}

private fun galleryClasses(vararg replace: MutableClass): List<ClassDef> =
    (systemCameraFixture() + externalGalleryFixture()).filter { cls -> replace.none { it.type == cls.type } } + replace

/** The gallery branch asks the extension first, and every way out of the hook but the start's yes lands on the stock code. */
private fun assertGalleryHook(before: List<Instruction>, after: List<Instruction>, site: Int, message: String) {
    assertEquals(before.unpadded().size + 15, after.unpadded().size, message)
    assertEquals(before.unpadded(), after.filterIndexed { i, _ -> i !in site until site + 15 }.unpadded(), message)
    val ask = after[site] as FiveRegisterInstruction
    assertEquals("$SETTINGS->chatGallery()Landroid/os/Bundle;", (ask as ReferenceInstruction).reference.toString(), message)
    assertEquals(0, ask.registerCount, message)
    val extras = (after[site + 1] as OneRegisterInstruction).registerA
    val start = after[site + 11] as FiveRegisterInstruction
    assertEquals(Opcode.INVOKE_VIRTUAL, after[site + 11].opcode, message)
    assertEquals(extras, start.registerD, message)
    assertEquals(galleryRoute!!.start, (start as ReferenceInstruction).reference.toString(), message)
    assertEquals(galleryRoute!!.provider, (after[site + 4] as ReferenceInstruction).reference.toString(), message)
    assertEquals(Opcode.RETURN_VOID, after[site + 14].opcode, message)
    val exits = (site until site + 14).filter { after[it].opcode == Opcode.IF_EQZ }
    assertEquals(4, exits.size, message)
    for (exit in exits) assertEquals(site + 15, after.branchTarget(exit), message)
    assertEquals(before[site], after[site + 15], message)
}

/** The result reader hands its request code, result code and intent to the extension before it reads any of them. */
private fun assertReaderHook(reader: MutableMethod, message: String) {
    val before = reader.code()
    reader.injectExternalGallery()
    val after = reader.code()
    assertEquals(before.unpadded().size + 1, after.unpadded().size, message)
    assertEquals(before.unpadded(), after.drop(1).unpadded(), message)
    assertEquals(Opcode.INVOKE_STATIC_RANGE, after[0].opcode, message)
    val ask = after[0] as RegisterRangeInstruction
    assertEquals(reader.implementation!!.registerCount - 3, ask.startRegister, message)
    assertEquals(3, ask.registerCount, message)
    assertEquals("$SETTINGS->externalMediaResult(IILandroid/content/Intent;)V", (ask as ReferenceInstruction).reference.toString(), message)
}

/** The camera and gallery switches both hook the launcher's start. Either order leaves one swap in it. */
private fun assertSharedStart(classes: List<ClassDef>, message: String) {
    for (cameraFirst in listOf(true, false)) {
        val camera = findSystemCamera(classes).associateBy { it.hookId() }
        val gallery = findExternalGallery(classes)
        val route = galleryRoute!!
        val start = MutableMethod(gallery.single { it.hookId() == route.start })
        val before = start.code()
        val site = start.chatCameraLaunchSite()
        val cameraStart = mapOf(SYSTEM_CAMERA to listOf(start))
        val galleryStart = mapOf(EXTERNAL_GALLERY to listOf(start))
        assertTrue(route.start in camera, message)
        if (cameraFirst) {
            injectControl(SYSTEM_CAMERA, cameraStart)
            injectControl(EXTERNAL_GALLERY, galleryStart)
        } else {
            injectControl(EXTERNAL_GALLERY, galleryStart)
            injectControl(SYSTEM_CAMERA, cameraStart)
        }
        val after = start.code()
        assertEquals(before.size + 4, after.size, "$message camera first $cameraFirst")
        assertEquals(1, after.count { (it as? ReferenceInstruction)?.reference?.toString() == "$SETTINGS->chatCamera(Landroid/content/Intent;)Landroid/content/Intent;" },
            "$message camera first $cameraFirst")
        assertEquals(before[site], after[site + 4], "$message camera first $cameraFirst")
    }
}

class ExternalGalleryTest {
    @AfterTest fun reset() {
        activeProfile = SYNTHETIC_PROFILE
        galleryRoute = null
    }

    @Test fun theGalleryButtonHandsTheChatCameraLauncherAMarkedBundle() {
        val found = findExternalGallery(galleryClasses())
        assertEquals(setOf(GALLERY_HANDLER, CHAT_CAMERA_START, COMPOSER_RESULT), found.map { it.hookId() }.toSet())
        assertEquals(GalleryRoute(GALLERY_HANDLER, GALLERY_PROVIDER, "LX/0c8;", "LX/5xZ;", CHAT_CAMERA_START, COMPOSER_RESULT), galleryRoute)
        validateControls(mapOf(EXTERNAL_GALLERY to found), setOf(EXTERNAL_GALLERY))
        val handler = found.single { it.hookId() == GALLERY_HANDLER } as MutableMethod
        val before = handler.code()
        val site = handler.gallerySite()
        // Right after the gallery event's cast, with the first three locals the branch writes before it reads.
        assertEquals(listOf(14, 1, 2, 3), listOf(site.at, site.extras, site.launcher, site.started))
        injectControl(EXTERNAL_GALLERY, mapOf(EXTERNAL_GALLERY to listOf(handler)))
        assertGalleryHook(before, handler.code(), site.at, GALLERY_HANDLER)
        assertReaderHook(MutableMethod(found.single { it.hookId() == COMPOSER_RESULT }), COMPOSER_RESULT)
    }

    @Test fun anAbstractLauncherHandsTheBundleOnlyToTheSubclassThatRunsItsStart() {
        val found = findExternalGallery(galleryClasses(*abstractLauncher()))
        assertEquals(setOf(GALLERY_HANDLER, CHAT_CAMERA_START, COMPOSER_RESULT), found.map { it.hookId() }.toSet())
        assertEquals("LX/5xY;", galleryRoute!!.launcher)
        val handler = found.single { it.hookId() == GALLERY_HANDLER } as MutableMethod
        val before = handler.code()
        val site = handler.gallerySite()
        handler.injectExternalGallery()
        val after = handler.code()
        assertGalleryHook(before, after, site.at, "abstract launcher")
        // A launcher of the subclass with its own start isn't one of these, so it gets Messenger's gallery.
        assertEquals(listOf(Opcode.INSTANCE_OF, Opcode.CHECK_CAST), listOf(after[site.at + 8].opcode, after[site.at + 10].opcode))
        for (at in listOf(site.at + 8, site.at + 10)) assertEquals("LX/5xY;", (after[at] as ReferenceInstruction).reference.toString())
        // Two subclasses that both run it, or none, leave no one class to check for.
        val twoPlain = abstractLauncher().dropLast(1) + fixtureClass("LX/5xW;", superclass = "LX/5xZ;", flags = FINAL_CLASS)
        val onlyOwn = abstractLauncher().filter { it.type != "LX/5xY;" }
        for ((case, classes) in listOf("two plain" to galleryClasses(*twoPlain.toTypedArray()), "only its own start" to galleryClasses(*onlyOwn.toTypedArray()))) {
            assertTrue(findExternalGallery(classes).isEmpty(), case)
            assertEquals(null, galleryRoute, case)
        }
    }

    @Test fun theCameraAndGallerySwitchesShareTheLauncherStartInEitherOrder() {
        assertSharedStart(galleryClasses(), "synthetic")
    }

    @Test fun aGalleryBranchTheHookCantEnterSafelyRefusesBeforeMutation() {
        findExternalGallery(galleryClasses())
        val jumped = GALLERY_HANDLER_BODY.replace("if-eqz v1, :gallery", "if-eqz v1, :entry")
            .replace("check-cast p1, $ACTIVATE_GALLERY\n", "check-cast p1, $ACTIVATE_GALLERY\n:entry\n")
        val selfWritten = GALLERY_HANDLER_BODY.replace(":other\nreturn-void", ":other\nconst/4 p0, 0x0\nreturn-void")
        val branched = GALLERY_HANDLER_BODY.replace("const-class v1, LX/5gW;", "if-eqz v4, :other\nconst-class v1, LX/5gW;")
        for ((body, registers) in listOf(jumped to 12, selfWritten to 12, branched to 12, GALLERY_HANDLER_BODY to 5)) {
            assertTrue(body != GALLERY_HANDLER_BODY || registers != 12)
            val handler = fixtureMethod(GALLERY_HANDLER, body, registers = registers)
            assertTrue(handler.isGalleryHandler())
            val before = handler.code()
            assertFailsWith<PatchException> { handler.injectExternalGallery() }
            assertEquals(before, handler.code())
        }
    }

    @Test fun onlyTheOneWayEditReachesTheLauncherOpensTheRoute() {
        assertTrue(findExternalGallery(galleryClasses()).isNotEmpty())
        // The provider read from some other object's field could hand over anything.
        val otherField = handlerClass(GALLERY_HANDLER_BODY.replace("iget-object v1, v0, $GALLERY_PROVIDER", "iget-object v1, v0, LX/6CD;->A0L:LX/0c8;"))
        // An Edit helper that never starts the launcher it takes doesn't prove what the provider holds.
        val otherStart = fixtureClass("LX/5JN;", listOf(fixtureMethod(GALLERY_EDITOR,
            galleryEditorBody("LX/6iH;->C0d(Landroid/os/Bundle;)Z"), registers = 4, flags = STATIC)))
        // A second handler of both events leaves the chat's one unknown.
        val twin = fixtureClass("LX/6CD;", listOf(fixtureMethod(GALLERY_HANDLER.replace("LX/6CC;", "LX/6CD;"),
            GALLERY_HANDLER_BODY.replace("LX/6CC;", "LX/6CD;"), registers = 12)))
        // A subclass, or one further down, that overrides the start would run instead of the hooked one.
        val overriding = fixtureClass("LX/5xY;", listOf(fixtureMethod(CHAT_CAMERA_START.replace("LX/5xZ;", "LX/5xY;"), STUB_START, registers = 2)),
            superclass = "LX/5xZ;")
        val middle = fixtureClass("LX/5xY;", superclass = "LX/5xZ;")
        val below = fixtureClass("LX/5xX;", listOf(fixtureMethod(CHAT_CAMERA_START.replace("LX/5xZ;", "LX/5xX;"), STUB_START, registers = 2)),
            superclass = "LX/5xY;")
        // A provider that isn't an interface can't take the hook's invoke-interface.
        val notInterface = fixtureClass("LX/0c8;")
        // A static result reader has no p1 to p3 the hook can hand over as they are.
        val staticReader = fixtureClass("LX/7Rm;", listOf(fixtureMethod(COMPOSER_RESULT, composerResultBody(), flags = STATIC)))
        for ((case, classes) in listOf("other field" to galleryClasses(otherField), "other start" to galleryClasses(otherStart),
            "twin" to galleryClasses() + twin, "override" to galleryClasses() + overriding,
            "override below" to galleryClasses() + middle + below, "not an interface" to galleryClasses(notInterface),
            "static reader" to galleryClasses(staticReader))) {
            assertTrue(findExternalGallery(classes).isEmpty(), case)
            assertEquals(null, galleryRoute, case)
        }
        // And without the chat camera's launcher there's nothing to hand the gallery button to.
        assertTrue(findExternalGallery(galleryClasses().filterNot { it.type == "LX/5qL;" }).isEmpty())
    }

    @Test fun theManifestAsksToSeeGalleryAppsOnce() {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for (manifest in listOf("<manifest package=\"com.facebook.orca\"><application/></manifest>",
            "<manifest package=\"com.facebook.orca\"><queries><package android:name=\"com.whatsapp\"/></queries><application/></manifest>")) {
            val document = builder.parse(manifest.byteInputStream())
            document.addGalleryQueries()
            assertEquals(1, document.getElementsByTagName("queries").length, manifest)
            val intents = (document.getElementsByTagName("queries").item(0) as Element).getElementsByTagName("intent")
            assertEquals(2, intents.length, manifest)
            val types = (0 until intents.length).map { i ->
                val intent = intents.item(i) as Element
                assertEquals("android.intent.action.PICK", (intent.getElementsByTagName("action").item(0) as Element).getAttribute("android:name"))
                (intent.getElementsByTagName("data").item(0) as Element).getAttribute("android:mimeType")
            }
            assertEquals(listOf("image/*", "video/*"), types, manifest)
        }
    }

    @Test fun everySupportedBuildHooksItsChatGalleryButton() {
        val root = System.getenv("HUSH_NATIVE_FIXTURES")
        assumeTrue(root != null, "Set HUSH_NATIVE_FIXTURES to the exact stock fixture directory")
        val apks = Files.list(Path.of(root!!)).use { it.filter { p -> p.toString().endsWith(".apk") }.sorted().toList() }
        assertEquals(controlProfiles.size, apks.size)
        for (apk in apks) {
            val code = apk.fileName.toString().substringBeforeLast(".apk").substringAfterLast('-')
            activeProfile = controlProfileFor(code)
            val dex = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.forApi(35))
            val classes = dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }
            val found = findExternalGallery(classes)
            assertEquals(activeProfile.hooks.getValue(EXTERNAL_GALLERY), found.map { it.hookId() }.toSet(), code)
            validateControls(mapOf(EXTERNAL_GALLERY to found), setOf(EXTERNAL_GALLERY))
            assertEquals(3, found.size, code)
            val handler = MutableMethod(found.single { it.hookId() == galleryRoute!!.handler })
            val before = handler.code()
            val site = handler.gallerySite()
            handler.injectExternalGallery()
            assertGalleryHook(before, handler.code(), site.at, code)
            // 583's launcher is abstract, so the hook checks for the one subclass that runs the hooked start.
            val target = classes.single { it.type == galleryRoute!!.launcher }
            assertTrue(!AccessFlags.ABSTRACT.isSet(target.accessFlags) && target.superclass == galleryRoute!!.start.substringBefore("->"), code)
            assertReaderHook(MutableMethod(found.single { it.hookId() == galleryRoute!!.reader }), code)
            assertSharedStart(classes, code)
        }
    }
}
