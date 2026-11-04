package io.github.bryceaspringfield.morphe.patches.reddit

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import io.github.bryceaspringfield.morphe.patches.reddit.Constants.COMPATIBILITY_REDDIT
import java.util.logging.Logger

private const val EXTENSION_PACKAGE = "Lio/github/bryceaspringfield/morphe/extension/reddit/HideKeywordContentPatch"
private const val EXTENSION_CLASS = "$EXTENSION_PACKAGE;"

/**
 * The settings screen Morphe Patches adds to Reddit (Settings > Morphe). This bundle adds its
 * section to that screen, and adds no screen of its own.
 */
private const val MORPHE_SETTINGS_FRAGMENT =
    "Lapp/morphe/extension/reddit/settings/preference/RedditPreferenceFragment;"

private val logger = Logger.getLogger("HideKeywordContentPatch")

@Suppress("unused")
val hideKeywordContentPatch = bytecodePatch(
    name = "Hide keyword content",
    description = "Hides feed posts by keywords in their title, text, community, or author. " +
            "Select it together with Morphe Patches' Reddit patches, whose settings screen " +
            "(Settings > Morphe) gets a Keyword filter section.",
    default = true
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    extendWith("extensions/reddit-keyword-filter.mpe")

    execute {
        /**
         * Tags the class of [fingerprint] with the extension's marker [interfaceName]. The extension
         * reads the element's values from its toString(), whose labels are the source property names.
         */
        fun addMarker(fingerprint: Fingerprint, interfaceName: String) {
            if (fingerprint.matchOrNull() == null) {
                logger.warning("Could not find the feed element for $interfaceName; its posts are not filtered")
                return
            }
            fingerprint.classDef.interfaces.add("$EXTENSION_PACKAGE\$$interfaceName;")
        }

        // Filter each page of the paged feeds (Home, Popular, Latest, communities, custom feeds).
        FeedPagingDataSourceLoadFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->filterFeedElements(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """
                )
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

        addMarker(PostElementFingerprint, "PostContainer")
        addMarker(ClassicPostElementFingerprint, "ClassicPost")
        addMarker(MetadataHeaderElementFingerprint, "HeaderElement")
        addMarker(PostTitleElementFingerprint, "TitleElement")
        addMarker(PreviewTextElementFingerprint, "TextElement")
    }

    // Runs after every selected patch of every bundle, when Morphe Patches' settings screen exists.
    finalize {
        val fragment = mutableClassDefByOrNull(MORPHE_SETTINGS_FRAGMENT)
        val initialize = fragment?.methods?.firstOrNull {
            it.name == "initialize" && it.parameterTypes.isEmpty() && it.returnType == "V"
        }
        if (initialize == null) {
            logger.warning(
                "Morphe Patches' Reddit settings screen is missing, so the keyword list cannot be edited. " +
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
                    addInstruction(
                        index,
                        "invoke-static { p0 }, $EXTENSION_CLASS->addSettings(Ljava/lang/Object;)V"
                    )
                }
        }
    }
}
