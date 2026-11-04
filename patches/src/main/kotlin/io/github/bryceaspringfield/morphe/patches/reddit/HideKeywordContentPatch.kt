package io.github.bryceaspringfield.morphe.patches.reddit

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.AccessFlags
import io.github.bryceaspringfield.morphe.patches.reddit.Constants.COMPATIBILITY_REDDIT
import java.util.logging.Logger

private const val EXTENSION_CLASS = "Lio/github/bryceaspringfield/morphe/extension/reddit/HideKeywordContentPatch;"

/**
 * The settings screen Morphe Patches adds to Reddit (Settings > Morphe). This bundle adds its
 * sections to that screen, and adds no screen of its own.
 */
private const val MORPHE_SETTINGS_FRAGMENT =
    "Lapp/morphe/extension/reddit/settings/preference/RedditPreferenceFragment;"

private const val BOTTOM_NAV_SCREEN = "Lcom/reddit/launch/bottomnav/BottomNavScreen;"
private const val BOTTOM_NAV_TAB = "Lcom/reddit/launch/bottomnav/BottomNavTab;"
private const val DRAWER_LIST_BINDER =
    "Lcom/reddit/screens/drawer/community/CommunityDrawerPresenter\$bindListOnView\$2;"
private const val MENU_BADGE_FLOW =
    "Lcom/reddit/navdrawer/composables/RedditMainNavigationButtonProvider\$mainNavigationIcon\$navIconBadgeVisible\$2\$1;"
private const val FEED_MODIFICATION =
    "Lcom/reddit/feeds/impl/domain/paging/RedditCachedFeedPager\$applyFeedModification\$2;"

/**
 * Whether a method is the app's copy of a collection into an immutable list of the type given: static, taking an
 * Iterable, and returning the Iterable itself when it is already of that type.
 */
private fun isImmutableCopy(method: Method, type: String) =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == type &&
            method.parameterTypes.map { it.toString() } == listOf("Ljava/lang/Iterable;") &&
            method.implementation?.instructions?.firstOrNull()?.let {
                it.opcode == Opcode.INSTANCE_OF && it.getReference<TypeReference>()?.type == type
            } == true

private val COMMENT_LISTS = listOf(
    "Lcom/reddit/domain/model/CommentsResultWithSource;",
    "Lcom/reddit/domain/model/CommentResponse;",
)

private val logger = Logger.getLogger("HideKeywordContentPatch")

@Suppress("unused")
val hideKeywordContentPatch = bytecodePatch(
    name = "Hide keyword content",
    description = "Hides posts and comments by keyword, community, author, flair, type, score, and number of " +
            "comments, and can hide the Inbox tab, sidebar rows, and the sidebar button's badge. Select it " +
            "together with Morphe Patches' Reddit patches, whose settings screen (Settings > Morphe) gets the " +
            "Keyword filter sections.",
    default = true
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    extendWith("extensions/reddit-keyword-filter.mpe")

    execute {
        // Filter each page of the paged feeds (Home, Popular, News, communities, search, profiles).
        FeedPagingDataSourceLoadFingerprint.let {
            it.method.apply {
                val getDataIndex = it.instructionMatches.first().index
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                // The data source, whose feed type names the place, is the receiver of the getClass() call
                // the compiled code makes just before getData().
                val getClassIndex = (getDataIndex - 1 downTo maxOf(0, getDataIndex - 12)).firstOrNull { i ->
                    val reference = getInstruction(i).getReference<MethodReference>()
                    reference?.name == "getClass" && reference.definingClass == "Ljava/lang/Object;"
                }
                // The compiled code may reuse the receiver's register after getClass(), keeping the source in a
                // copy made just before; the first candidate no instruction overwrites before the hook is used.
                fun keptUntilHook(register: Int, from: Int) = (from..index).none { i ->
                    val instruction = getInstruction(i)
                    instruction.opcode.setsRegister() &&
                            (instruction as? OneRegisterInstruction)?.registerA == register
                }
                val sourceRegister = getClassIndex?.let { getClassAt ->
                    val receiver = getInstruction<FiveRegisterInstruction>(getClassAt).registerC
                    val copies = (getClassAt - 1 downTo maxOf(0, getClassAt - 8)).mapNotNull { i ->
                        val instruction = getInstruction(i)
                        if (instruction.opcode == Opcode.MOVE_OBJECT || instruction.opcode == Opcode.MOVE_OBJECT_FROM16)
                            (instruction as TwoRegisterInstruction).takeIf { it.registerB == receiver }?.registerA
                        else null
                    }
                    (listOf(receiver) + copies).firstOrNull { keptUntilHook(it, getClassAt + 1) }
                }
                val sourceKept = sourceRegister != null

                addInstructions(
                    index + 1,
                    """
                        invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->filterFeedElements(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """
                )
                if (sourceKept) {
                    addInstruction(
                        index + 1,
                        "invoke-static/range { v$sourceRegister .. v$sourceRegister }, " +
                                "$EXTENSION_CLASS->setFeedSource(Ljava/lang/Object;)V"
                    )
                } else {
                    logger.warning("Could not find the feed's data source; feeds are filtered as Other feeds")
                }
            }
        }

        // Filter the older post listings (search results, profiles).
        ListingFingerprint.method.apply {
            // Found by scanning, since another bundle's patch may have inserted code here after matching.
            val index = instructions.indexOfFirst {
                it.opcode == Opcode.IPUT_OBJECT && it.getReference<FieldReference>()?.name == "children"
            }
            val register = getInstruction<TwoRegisterInstruction>(index).registerA

            addInstructions(
                index,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS->filterLinks(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$register
                """
            )
        }
    }

    // Runs after every selected patch of every bundle, so Morphe Patches' own hooks in the same
    // methods are in place, and Morphe Patches' settings screen exists.
    finalize {
        // Filter a feed again after a translation rewrites its posts: the result of the pager's modification step, at the
        // modification call and at the return of a resumed call.
        run {
            val method = mutableClassDefByOrNull(FEED_MODIFICATION)?.methods?.firstOrNull { it.name == "invokeSuspend" }
            val listType = method?.instructions?.firstNotNullOfOrNull {
                if (it.opcode == Opcode.CHECK_CAST) it.getReference<TypeReference>()?.type else null
            }
            val callIndex = method?.instructions?.indexOfFirst { it.opcode == Opcode.INVOKE_INTERFACE } ?: -1
            val modificationType = method?.takeIf { callIndex >= 0 }?.getInstruction(callIndex)
                ?.getReference<MethodReference>()?.definingClass
            val field = method?.instructions?.firstNotNullOfOrNull {
                it.getReference<FieldReference>()?.takeIf { f -> it.opcode == Opcode.IGET_OBJECT && f.type == modificationType }
            }
            val copy = listType?.let { type ->
                classDefByOrNull { c -> c.methods.any { isImmutableCopy(it, type) } }?.methods?.first { isImmutableCopy(it, type) }
            }
            val stub = mutableClassDefByOrNull(EXTENSION_CLASS)?.methods?.firstOrNull { it.name == "immutableList" }
            if (method == null || field == null || copy == null || stub == null ||
                method.getInstruction(callIndex + 1).opcode != Opcode.MOVE_RESULT_OBJECT
            ) {
                logger.warning("Could not find the feed's modification step; translated posts are not filtered again")
                return@run
            }

            // The extension's list copy calls the app's.
            stub.addInstructions(
                0,
                """
                    invoke-static { p0 }, ${copy.definingClass}->${copy.name}(Ljava/lang/Iterable;)${copy.returnType}
                    move-result-object p0
                    return-object p0
                """
            )

            method.apply {
                val thisRegister = implementation!!.registerCount - 2
                // A return reached before any instruction writes the register of this, which then still holds this.
                val resumeReturn = instructions.withIndex().firstOrNull { (i, it) ->
                    it.opcode == Opcode.RETURN_OBJECT && (0 until i).none { j ->
                        val previous = getInstruction(j)
                        previous.opcode.setsRegister() && (previous as? OneRegisterInstruction)?.registerA == thisRegister
                    }
                }?.index
                val call = getInstruction<FiveRegisterInstruction>(callIndex)
                val receiver = call.registerC
                val result = getInstruction<OneRegisterInstruction>(callIndex + 1).registerA

                // Inserted from the last index to the first, so earlier indices stay valid.
                val hooks = mutableListOf<Pair<Int, String>>()
                if (resumeReturn != null) {
                    val value = getInstruction<OneRegisterInstruction>(resumeReturn).registerA
                    val temp = (0..2).first { it != value && it != thisRegister }
                    if (value < 16 && thisRegister < 16) hooks += resumeReturn to """
                        iget-object v$temp, p0, ${field.definingClass}->${field.name}:${field.type}
                        invoke-static { v$value, v$temp }, $EXTENSION_CLASS->filterModifiedFeed(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$value
                    """
                }
                if (receiver != result && receiver < 16 && result < 16) hooks += (callIndex + 2) to """
                    invoke-static { v$result, v$receiver }, $EXTENSION_CLASS->filterModifiedFeed(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$result
                """ else logger.warning("The modification call's registers are unexpected; synchronous results are not filtered again")
                hooks.sortedByDescending { it.first }.forEach { (index, code) -> addInstructions(index, code) }
            }
        }

        // Filter each post's comments where the app stores them.
        COMMENT_LISTS.forEach { type ->
            val constructors = mutableClassDefByOrNull(type)?.methods?.filter { method ->
                method.name == "<init>" && "Ljava/util/List;" in method.parameterTypes &&
                        "Lkotlin/jvm/internal/DefaultConstructorMarker;" !in method.parameterTypes
            }.orEmpty()
            if (constructors.isEmpty()) logger.warning("Could not find $type; comments there are not filtered")
            constructors.forEach { method ->
                val types = method.parameterTypes.map { it.toString() }
                val position = types.indexOf("Ljava/util/List;")
                val register = 1 + types.take(position).sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt()
                method.addInstructions(
                    0,
                    """
                        invoke-static/range { p$register .. p$register }, $EXTENSION_CLASS->filterComments(Ljava/util/List;)Ljava/util/List;
                        move-result-object p$register
                    """
                )
            }
        }

        // Skip a hidden bottom bar tab in the loop building the bar's tabs.
        val tabBuilder = mutableClassDefByOrNull(BOTTOM_NAV_SCREEN)?.methods?.firstOrNull { method ->
            method.returnType != "V" && method.parameterTypes.size == 1 && method.implementation != null &&
                    method.instructions.any { it.getReference<MethodReference>()?.name == "hasNext" } &&
                    method.instructions.any {
                        it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>()?.type == BOTTOM_NAV_TAB
                    }
        }
        if (tabBuilder == null) {
            logger.warning("Could not find the bottom bar's tab builder; the Inbox tab cannot be hidden")
        } else tabBuilder.apply {
            val loopIndex = instructions.indexOfFirst { it.getReference<MethodReference>()?.name == "hasNext" }
            val tabIndex = instructions.withIndex().first { (i, it) ->
                i > loopIndex && it.opcode == Opcode.IGET_OBJECT &&
                        it.getReference<FieldReference>()?.type == BOTTOM_NAV_TAB
            }.index
            val register = getInstruction<TwoRegisterInstruction>(tabIndex).registerA
            addInstructionsWithLabels(
                tabIndex + 1,
                """
                    invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->navigationTab(Ljava/lang/Enum;)Ljava/lang/Enum;
                    move-result-object v$register
                    if-eqz v$register, :next_tab
                    check-cast v$register, $BOTTOM_NAV_TAB
                """,
                ExternalLabel("next_tab", getInstruction(loopIndex))
            )
        }

        // Remove hidden sidebar rows before the sidebar's adapter gets its list.
        val drawerBinder = mutableClassDefByOrNull(DRAWER_LIST_BINDER)?.methods?.firstOrNull { it.name == "invokeSuspend" }
        val drawerCall = drawerBinder?.instructions?.indexOfFirst {
            val reference = it.getReference<MethodReference>()
            it.opcode == Opcode.INVOKE_VIRTUAL && reference?.returnType == "V" &&
                    reference.parameterTypes.map { type -> type.toString() } == listOf("Ljava/util/List;")
        } ?: -1
        if (drawerBinder == null || drawerCall < 0) {
            logger.warning("Could not find the sidebar's list; sidebar rows cannot be hidden")
        } else drawerBinder.apply {
            val register = getInstruction<FiveRegisterInstruction>(drawerCall).registerD
            addInstructions(
                drawerCall,
                """
                    invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->filterSidebar(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$register
                """
            )
        }

        // End the sidebar button's badge flow before it emits, so the badge stays hidden.
        val badgeFlow = mutableClassDefByOrNull(MENU_BADGE_FLOW)?.methods?.firstOrNull { it.name == "invokeSuspend" }
        val unit = badgeFlow?.instructions?.firstNotNullOfOrNull {
            it.getReference<FieldReference>()?.takeIf { field -> it.opcode == Opcode.SGET_OBJECT && field.type == "Lkotlin/Unit;" }
        }
        if (badgeFlow == null || unit == null) {
            logger.warning("Could not find the sidebar button's badge; it cannot be hidden")
        } else badgeFlow.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->hideMenuBadge()Z
                    move-result v0
                    if-eqz v0, :show_badge
                    sget-object v0, ${unit.definingClass}->${unit.name}:${unit.type}
                    return-object v0
                """,
                ExternalLabel("show_badge", getInstruction(0))
            )
        }

        val fragment = mutableClassDefByOrNull(MORPHE_SETTINGS_FRAGMENT)
        val initialize = fragment?.methods?.firstOrNull {
            it.name == "initialize" && it.parameterTypes.isEmpty() && it.returnType == "V"
        }
        if (initialize == null) {
            logger.warning(
                "Morphe Patches' Reddit settings screen is missing, so the filters cannot be edited. " +
                        "Also select Morphe Patches' Reddit patches."
            )
            return@finalize
        }

        initialize.apply {
            // From the last return to the first, so earlier indices stay valid.
            instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_VOID }
                .map { it.index }
                .reversed()
                .forEach { index ->
                    addInstruction(index, "invoke-static { }, $EXTENSION_CLASS->addSettings()V")
                }

            // The screen is saved at the start, since the compiled method reuses the register of
            // `this` for its own values before it returns.
            addInstruction(0, "invoke-static { p0 }, $EXTENSION_CLASS->setSettingsFragment(Ljava/lang/Object;)V")
        }
    }
}
