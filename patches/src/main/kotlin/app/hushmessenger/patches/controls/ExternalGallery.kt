package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.w3c.dom.Document
import org.w3c.dom.Element

internal const val EXTERNAL_GALLERY = "external_gallery"
internal const val ACTIVATE_GALLERY = "Lcom/facebook/xapp/messaging/composer/gallery/event/ActivateGalleryEvent;"
internal const val EDIT_GALLERY_ITEM = "Lcom/facebook/xapp/messaging/composer/gallery/event/EditGalleryItemEvent;"
private const val CHAT_GALLERY_CALL = "$SETTINGS->chatGallery()Landroid/os/Bundle;"
private const val MEDIA_RESULT_CALL = "$SETTINGS->externalMediaResult(IILandroid/content/Intent;)V"
private const val PROVIDER_GET = "get()Ljava/lang/Object;"

/**
 * How the chat's gallery button reaches the chat camera's launcher, read at discovery: the handler, its field holding
 * the launcher's provider and that provider's interface, then the launcher class that runs the start, the start, and
 * the chat's result reader.
 */
internal data class GalleryRoute(val handler: String, val provider: String, val providerType: String, val launcher: String, val start: String,
    val reader: String)

/** Set by [findExternalGallery] and cleared once the controls are in. */
internal var galleryRoute: GalleryRoute? = null

private fun galleryChanged(detail: String): Nothing =
    throw PatchException("Messenger controls: the chat gallery button moved ($detail)")

private fun Instruction.galleryMethod() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.galleryField() = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Instruction.galleryType() = ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type
private fun Method.galleryStrings() = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }?.toSet().orEmpty()
private fun String.galleryPackage() = substringBeforeLast('/', "")
private fun dotted(type: String) = type.removePrefix("L").removeSuffix(";").replace('/', '.')

/** The registers an invoke passes, in order. */
private fun Instruction.galleryArguments(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

/** Whether this writes [register], whole or as half of a wide pair. */
private fun Instruction.galleryWrites(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

/**
 * Every register this may read, erring toward more: the second register of each for anything wide, and the destination
 * too unless the instruction only writes it.
 */
private fun Instruction.galleryReads(): Set<Int> {
    val onlyWrites = opcode.setsRegister() && opcode != Opcode.CHECK_CAST && !opcode.name.endsWith("_2ADDR")
    val read = when (this) {
        is FiveRegisterInstruction, is RegisterRangeInstruction -> galleryArguments()
        is ThreeRegisterInstruction -> listOfNotNull(registerA.takeUnless { onlyWrites }, registerB, registerC)
        is TwoRegisterInstruction -> listOfNotNull(registerA.takeUnless { onlyWrites }, registerB)
        is OneRegisterInstruction -> listOfNotNull(registerA.takeUnless { onlyWrites })
        else -> emptyList()
    }
    val wide = listOf("WIDE", "LONG", "DOUBLE").any { it in opcode.name }
    return read.flatMap { if (wide) listOf(it, it + 1) else listOf(it) }.toSet()
}

/** Whether a run of straight code that ends returning nothing writes [register] before it reads it, or never touches it. */
private fun List<Instruction>.galleryFree(register: Int): Boolean {
    for (instruction in this) {
        if (register in instruction.galleryReads()) return false
        if (instruction.galleryWrites(register)) return true
    }
    return true
}

private fun ClassDef.galleryImplements(iface: String, byType: Map<String, ClassDef>): Boolean =
    generateSequence(this) { cls -> cls.superclass?.let(byType::get) }.take(16).any { iface in it.interfaces }

/**
 * The one class whose objects run [start] as [launcher] declares it, or none. Messenger 583's launcher is abstract, with
 * one final subclass that runs this start and another that brings its own, and the hook calls the start virtually, so it
 * only hands its bundle to the first kind.
 */
private fun List<ClassDef>.galleryTarget(launcher: ClassDef, start: Method, byType: Map<String, ClassDef>): ClassDef? {
    val signature = start.parameterTypes.map { it.toString() }
    fun ClassDef.declaresStart() = methods.any {
        it.name == start.name && it.returnType == start.returnType && it.parameterTypes.map { p -> p.toString() } == signature
    }
    fun ClassDef.lineage() = generateSequence(this) { cls -> cls.superclass?.let(byType::get) }.take(16).toList()
    val below = filter { cls -> cls.type != launcher.type && cls.lineage().any { it.type == launcher.type } }
    fun ClassDef.runsStart() = lineage().takeWhile { it.type != launcher.type }.none { it.declaresStart() }
    return (listOf(launcher) + below).filter { cls ->
        !AccessFlags.ABSTRACT.isSet(cls.accessFlags) && cls.runsStart() &&
            below.none { it.declaresStart() && it.lineage().any { up -> up.type == cls.type } }
    }.singleOrNull()
}

/**
 * The chat composer's event handler. It opens Messenger's own gallery on ActivateGalleryEvent and Messenger's editor on
 * EditGalleryItemEvent, both by the event's class name.
 */
internal fun Method.isGalleryHandler(): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags) || returnType != "V" || parameterTypes.size != 2 ||
        parameterTypes.last().toString() != "Ljava/lang/String;") return false
    val code = implementation?.instructions ?: return false
    val strings = galleryStrings()
    return dotted(ACTIVATE_GALLERY) in strings && dotted(EDIT_GALLERY_ITEM) in strings &&
        code.count { it.opcode == Opcode.CHECK_CAST && it.galleryType() == ACTIVATE_GALLERY } == 1
}

/**
 * The handler's field the Edit branch reads the chat camera's launcher from, the same launcher the gallery button gets:
 *
 *     iget-object vProvider, p0, <handler>->field:<Provider>
 *     ...
 *     invoke-static {..., vEvent, vProvider}, <editor>(..., EditGalleryItemEvent, <Provider>)V
 *
 * and in the editor, on its last parameter:
 *
 *     invoke-interface {pProvider}, <Provider>->get()Ljava/lang/Object;
 *     move-result-object vLauncher
 *     check-cast vLauncher, <launcher interface>
 *     invoke-interface {vLauncher, vExtras}, <launcher interface>-><start>(Landroid/os/Bundle;)Z
 */
private fun Method.galleryProvider(iface: String, startName: String, byType: Map<String, ClassDef>): FieldReference? {
    val code = implementation!!.instructions.toList()
    val edits = code.indices.filter { at ->
        code[at].opcode == Opcode.INVOKE_STATIC && code[at].galleryMethod()?.parameterTypes?.any { it.toString() == EDIT_GALLERY_ITEM } == true
    }
    val e = edits.singleOrNull() ?: return null
    val edit = code[e].galleryMethod()!!
    val providerType = edit.parameterTypes.lastOrNull()?.toString() ?: return null
    val provider = code[e].galleryArguments().lastOrNull() ?: return null
    val load = (e - 1 downTo 0).firstOrNull { code[it].galleryWrites(provider) } ?: return null
    val field = code[load].takeIf { it.opcode == Opcode.IGET_OBJECT }?.galleryField() ?: return null
    if (field.definingClass != definingClass || field.type != providerType) return null
    val jumps = jumpTargets()
    if ((load + 1..e).any { it in jumps || code[it] is OffsetInstruction }) return null
    byType[definingClass]?.fields?.singleOrNull { it.name == field.name && it.type == field.type && !AccessFlags.STATIC.isSet(it.accessFlags) }
        ?: return null
    val editor = byType[edit.definingClass]?.methods?.singleOrNull { it.hookId() == edit.toString() } ?: return null
    return field.takeIf { editor.startsFromProvider(providerType, "$iface->$startName(Landroid/os/Bundle;)Z") }
}

private fun Method.startsFromProvider(providerType: String, start: String): Boolean {
    val code = implementation?.instructions?.toList() ?: return false
    if (parameterTypes.lastOrNull()?.toString() != providerType) return false
    val provider = implementation!!.registerCount - 1
    if (code.any { it.galleryWrites(provider) }) return false
    val gets = code.indices.filter { at ->
        code[at].opcode == Opcode.INVOKE_INTERFACE && code[at].galleryArguments() == listOf(provider) &&
            code[at].galleryMethod()?.toString() == "$providerType->$PROVIDER_GET"
    }
    val g = gets.singleOrNull() ?: return false
    val launcher = (code.getOrNull(g + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction)?.registerA
        ?: return false
    val cast = code.getOrNull(g + 2)
    if (cast?.opcode != Opcode.CHECK_CAST || (cast as OneRegisterInstruction).registerA != launcher ||
        cast.galleryType() != start.substringBefore("->")) return false
    for (at in g + 3 until code.size) {
        val instruction = code[at]
        if (instruction.opcode == Opcode.INVOKE_INTERFACE && instruction.galleryMethod()?.toString() == start)
            return instruction.galleryArguments().firstOrNull() == launcher
        if (instruction.galleryWrites(launcher) || instruction is OffsetInstruction) return false
    }
    return false
}

/**
 * The chat's gallery event handler, the chat camera launcher's start and the chat's result reader, or none. The handler
 * hands the launcher a marked bundle, and the start swaps that launch for the phone's gallery app, so a pick comes back
 * to the chat that asked under the request code Messenger reads a photo from another app under. The reader takes the
 * pick's type from the result, and a gallery app often sends none, so the hook there fills it in first. That needs the
 * chat camera's launcher exactly as the camera switch finds it, the one way the Edit branch reaches it, and both
 * reachable from the handler.
 */
internal fun findExternalGallery(classes: Iterable<ClassDef>): List<Method> {
    galleryRoute = null
    val all = classes.toList()
    val methods = all.flatMap { it.methods }
    val readers = methods.filter { it.isComposerResult() }
    val reader = readers.singleOrNull() ?: return emptyList()
    if (AccessFlags.STATIC.isSet(reader.accessFlags) ||
        reader.implementation!!.instructions.none { (it as? NarrowLiteralInstruction)?.narrowLiteral == CHAT_CAMERA_REQUEST }) return emptyList()
    val factory = methods.filter { it.isChatCameraFactory() }.singleOrNull() ?: return emptyList()
    val launcher = factory.chatCameraLauncherType() ?: return emptyList()
    val byType = all.associateBy { it.type }
    val launcherClass = byType[launcher] ?: return emptyList()
    if (!launcherClass.galleryImplements(factory.returnType, byType)) return emptyList()
    val start = launcherClass.methods.filter { it.isLauncherStart() }.singleOrNull() ?: return emptyList()
    val target = all.galleryTarget(launcherClass, start, byType) ?: return emptyList()
    val handler = methods.filter { it.isGalleryHandler() }.singleOrNull() ?: return emptyList()
    val provider = handler.galleryProvider(factory.returnType, start.name, byType) ?: return emptyList()
    val providerClass = byType[provider.type] ?: return emptyList()
    val home = handler.definingClass.galleryPackage()
    fun reachable(cls: ClassDef) = AccessFlags.PUBLIC.isSet(cls.accessFlags) || cls.type.galleryPackage() == home
    if (!AccessFlags.INTERFACE.isSet(providerClass.accessFlags) || !reachable(providerClass) || !reachable(launcherClass) ||
        !reachable(target) || AccessFlags.PRIVATE.isSet(start.accessFlags) ||
        (!AccessFlags.PUBLIC.isSet(start.accessFlags) && launcher.galleryPackage() != home)) return emptyList()
    galleryRoute = GalleryRoute(handler.hookId(), provider.toString(), provider.type, target.type, start.hookId(), reader.hookId())
    return listOf(handler, start, reader)
}

/** Where the handler's gallery branch starts, and three locals that branch writes before it reads them. */
internal class GallerySite(val at: Int, val extras: Int, val launcher: Int, val started: Int)

/**
 * Right after the handler casts its ActivateGalleryEvent. From there the branch runs straight to its return-void, outside
 * any try, so a register it writes before reading is free for the hook on its way in.
 */
internal fun Method.gallerySite(): GallerySite {
    val code = implementation?.instructions?.toList() ?: galleryChanged("no code")
    val casts = code.indices.filter { code[it].opcode == Opcode.CHECK_CAST && code[it].galleryType() == ACTIVATE_GALLERY }
    val at = (casts.singleOrNull() ?: galleryChanged("${casts.size} gallery events")) + 1
    val end = (at until code.size).firstOrNull { code[it].opcode == Opcode.RETURN_VOID } ?: galleryChanged("no return after the gallery event")
    val run = code.subList(at, end + 1)
    if (run.any { it is OffsetInstruction }) galleryChanged("a branch in the gallery branch")
    if (at in jumpTargets()) galleryChanged("a branch lands on the gallery branch")
    // A catch handler could read what the hook writes.
    val addresses = code.take(end + 1).runningFold(0) { address, instruction -> address + instruction.codeUnits }
    if (implementation!!.tryBlocks.any { it.startCodeAddress < addresses[end + 1] && it.startCodeAddress + it.codeUnitCount > addresses[at] })
        galleryChanged("the gallery branch is inside a try")
    val self = parameterRegister(0)
    if (code.any { it.galleryWrites(self) }) galleryChanged("the handler's own register is reused")
    // The hook's plain invokes and branches take registers up to v15, and only locals sit below the parameters.
    val free = (0 until minOf(16, self)).filter { run.galleryFree(it) }
    if (free.size < 3) galleryChanged("no free registers")
    return GallerySite(at, free[0], free[1], free[2])
}

internal fun MutableMethod.validateExternalGallery(): Int {
    val route = galleryRoute ?: galleryChanged("no route")
    return when (hookId()) {
        // The camera switch hooks the same start the same way, so whichever comes second leaves it as it is.
        route.start -> if (chatStartRouted()) -1 else chatCameraLaunchSite()
        route.handler -> gallerySite().at
        // The result reader's own parameters, before anything in it runs.
        route.reader -> if (!AccessFlags.STATIC.isSet(accessFlags) && implementation != null) 0 else galleryChanged("the result reader")
        else -> throw PatchException("Messenger controls: unexpected gallery hook ${hookId()}")
    }
}

/**
 * With the switch on, the gallery button hands the chat camera's launcher a marked bundle, which its start turns into a
 * pick in the phone's gallery app. Off, Pause, safe mode, no gallery app, or a launcher that didn't start get Messenger's
 * own gallery as before.
 */
internal fun MutableMethod.injectExternalGallery() {
    val at = validateExternalGallery()
    val route = galleryRoute!!
    if (hookId() == route.start) {
        if (at >= 0) injectChatCameraStart(at)
        return
    }
    if (hookId() == route.reader) {
        addInstructions(at, "invoke-static/range {p1 .. p3}, $MEDIA_RESULT_CALL")
        return
    }
    val site = gallerySite()
    val extras = "v${site.extras}"
    val launcher = "v${site.launcher}"
    val started = "v${site.started}"
    addInstructionsWithLabels(at, """
        invoke-static {}, $CHAT_GALLERY_CALL
        move-result-object $extras
        if-eqz $extras, :stock_gallery
        move-object/from16 $launcher, p0
        iget-object $launcher, $launcher, ${route.provider}
        if-eqz $launcher, :stock_gallery
        invoke-interface {$launcher}, ${route.providerType}->$PROVIDER_GET
        move-result-object $launcher
        instance-of $started, $launcher, ${route.launcher}
        if-eqz $started, :stock_gallery
        check-cast $launcher, ${route.launcher}
        invoke-virtual {$launcher, $extras}, ${route.start}
        move-result $started
        if-eqz $started, :stock_gallery
        return-void
    """.trimIndent(), ExternalLabel("stock_gallery", getInstruction(at)))
}

/** Android hides other apps from Messenger unless its manifest says what it looks for, here a photo or video pick. */
internal fun Document.addGalleryQueries() {
    val root = documentElement ?: throw PatchException("Messenger controls: the manifest has no root")
    val children = root.childNodes
    val queries = (0 until children.length).map { children.item(it) }.filterIsInstance<Element>().firstOrNull { it.tagName == "queries" }
        ?: createElement("queries").also { root.appendChild(it) }
    for (type in listOf("image/*", "video/*")) {
        val intent = createElement("intent")
        intent.appendChild(createElement("action").also { it.setAttribute("android:name", "android.intent.action.PICK") })
        intent.appendChild(createElement("data").also { it.setAttribute("android:mimeType", type) })
        queries.appendChild(intent)
    }
}
