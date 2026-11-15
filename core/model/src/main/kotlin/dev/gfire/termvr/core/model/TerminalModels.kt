package dev.gfire.termvr.core.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * Configures the dockable two-row auxiliary modifier and navigation key bar.
 */
@Serializable
data class KeyBarConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val visible: Boolean = true,
    val hapticFeedback: Boolean = true,
    val topRow: List<KeySlotSpec> = DEFAULT_TOP_ROW,
    val bottomRow: List<KeySlotSpec> = DEFAULT_BOTTOM_ROW,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported KeyBarConfig schemaVersion: $schemaVersion"
        }
        require(topRow.size == ROW_SLOT_COUNT) {
            "KeyBarConfig topRow must contain $ROW_SLOT_COUNT slots, found ${topRow.size}"
        }
        require(bottomRow.size == ROW_SLOT_COUNT) {
            "KeyBarConfig bottomRow must contain $ROW_SLOT_COUNT slots, found ${bottomRow.size}"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val ROW_SLOT_COUNT: Int = 7

        val DEFAULT_TOP_ROW: List<KeySlotSpec> = listOf(
            KeySlotSpec.GhosttyKey(keyName = "ESCAPE", label = "ESC"),
            KeySlotSpec.LiteralText(text = "/", label = "/"),
            KeySlotSpec.LiteralText(text = "-", label = "-"),
            KeySlotSpec.GhosttyKey(keyName = "HOME", label = "HOME"),
            KeySlotSpec.GhosttyKey(keyName = "UP", label = "UP"),
            KeySlotSpec.GhosttyKey(keyName = "END", label = "END"),
            KeySlotSpec.GhosttyKey(keyName = "PAGE_UP", label = "PGUP"),
        )

        val DEFAULT_BOTTOM_ROW: List<KeySlotSpec> = listOf(
            KeySlotSpec.GhosttyKey(keyName = "TAB", label = "TAB"),
            KeySlotSpec.Modifier(mod = ModifierKey.CTRL),
            KeySlotSpec.Modifier(mod = ModifierKey.ALT),
            KeySlotSpec.GhosttyKey(keyName = "LEFT", label = "LEFT"),
            KeySlotSpec.GhosttyKey(keyName = "DOWN", label = "DOWN"),
            KeySlotSpec.GhosttyKey(keyName = "RIGHT", label = "RIGHT"),
            KeySlotSpec.GhosttyKey(keyName = "PAGE_DOWN", label = "PGDN"),
        )
    }
}

/**
 * Defines one customizable slot inside an auxiliary key bar row.
 */
@Serializable
sealed interface KeySlotSpec {
    @Serializable
    data class Modifier(val mod: ModifierKey) : KeySlotSpec

    @Serializable
    data class GhosttyKey(val keyName: String, val label: String) : KeySlotSpec {
        init {
            require(keyName.isNotBlank()) { "GhosttyKey keyName must not be blank" }
            require(label.isNotBlank()) { "GhosttyKey label must not be blank" }
        }
    }

    @Serializable
    data class LiteralText(val text: String, val label: String) : KeySlotSpec {
        init {
            require(text.isNotEmpty()) { "LiteralText text must not be empty" }
            require(label.isNotBlank()) { "LiteralText label must not be blank" }
        }
    }

    @Serializable
    data class CtrlChord(val asciiUpper: Char, val label: String) : KeySlotSpec {
        init {
            require(asciiUpper in 'A'..'Z') {
                "CtrlChord asciiUpper must be in A..Z, found '$asciiUpper'"
            }
            require(label.isNotBlank()) { "CtrlChord label must not be blank" }
        }
    }
}

@Serializable
enum class ModifierKey {
    CTRL,
    ALT,
    SHIFT,
    SUPER,
}

@Serializable
enum class ModifierLatchState {
    RELEASED,
    ONE_SHOT,
    LOCKED,
    HELD,
}

/**
 * Defines terminal font selection, cell metrics, ligature shaping, and SHA-256 verification state.
 */
@Serializable
data class FontConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val familyId: String = DEFAULT_FAMILY_ID,
    val customFontPath: String? = null,
    val customFontSha256: String? = null,
    val sizeSp: Float = DEFAULT_SIZE_SP,
    val cellHeightMultiplier: Float = DEFAULT_CELL_HEIGHT_MULTIPLIER,
    val ligaturesEnabled: Boolean = true,
    val nerdFontSymbolsEnabled: Boolean = true,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported FontConfig schemaVersion: $schemaVersion"
        }
        require(familyId.isNotBlank()) { "FontConfig familyId must not be blank" }
        require(sizeSp.isFinite() && sizeSp in MIN_SIZE_SP..MAX_SIZE_SP) {
            "FontConfig sizeSp must be within $MIN_SIZE_SP..$MAX_SIZE_SP, found $sizeSp"
        }
        require(
            cellHeightMultiplier.isFinite() &&
                cellHeightMultiplier in MIN_CELL_HEIGHT_MULTIPLIER..MAX_CELL_HEIGHT_MULTIPLIER,
        ) {
            "FontConfig cellHeightMultiplier must be within " +
                "$MIN_CELL_HEIGHT_MULTIPLIER..$MAX_CELL_HEIGHT_MULTIPLIER, found $cellHeightMultiplier"
        }
        if (customFontPath != null) {
            require(customFontPath.isNotBlank()) { "FontConfig customFontPath must not be blank" }
        }
        if (customFontSha256 != null) {
            require(SHA256_HEX_REGEX.matches(customFontSha256)) {
                "FontConfig customFontSha256 must be 64 lowercase hexadecimal characters"
            }
        }
    }

    fun withClampedMetrics(
        newSizeSp: Float = sizeSp,
        newCellHeightMultiplier: Float = cellHeightMultiplier,
    ): FontConfig = copy(
        sizeSp = clampSizeSp(newSizeSp),
        cellHeightMultiplier = clampCellHeightMultiplier(newCellHeightMultiplier),
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val DEFAULT_FAMILY_ID: String = "jetbrains-mono"
        const val DEFAULT_SIZE_SP: Float = 13.0f
        const val MIN_SIZE_SP: Float = 8.0f
        const val MAX_SIZE_SP: Float = 32.0f
        const val SIZE_STEP_SP: Float = 0.5f
        const val DEFAULT_CELL_HEIGHT_MULTIPLIER: Float = 1.15f
        const val MIN_CELL_HEIGHT_MULTIPLIER: Float = 1.0f
        const val MAX_CELL_HEIGHT_MULTIPLIER: Float = 1.6f

        private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")

        fun clampSizeSp(rawSp: Float): Float {
            require(!rawSp.isNaN()) { "Font sizeSp must not be NaN" }
            val bounded = rawSp.coerceIn(MIN_SIZE_SP, MAX_SIZE_SP)
            return (bounded / SIZE_STEP_SP).roundToInt() * SIZE_STEP_SP
        }

        fun clampCellHeightMultiplier(rawMultiplier: Float): Float {
            require(!rawMultiplier.isNaN()) { "Cell height multiplier must not be NaN" }
            return rawMultiplier.coerceIn(MIN_CELL_HEIGHT_MULTIPLIER, MAX_CELL_HEIGHT_MULTIPLIER)
        }

        fun clamped(
            familyId: String = DEFAULT_FAMILY_ID,
            customFontPath: String? = null,
            customFontSha256: String? = null,
            sizeSp: Float = DEFAULT_SIZE_SP,
            cellHeightMultiplier: Float = DEFAULT_CELL_HEIGHT_MULTIPLIER,
            ligaturesEnabled: Boolean = true,
            nerdFontSymbolsEnabled: Boolean = true,
        ): FontConfig = FontConfig(
            familyId = familyId,
            customFontPath = customFontPath,
            customFontSha256 = customFontSha256,
            sizeSp = clampSizeSp(sizeSp),
            cellHeightMultiplier = clampCellHeightMultiplier(cellHeightMultiplier),
            ligaturesEnabled = ligaturesEnabled,
            nerdFontSymbolsEnabled = nerdFontSymbolsEnabled,
        )
    }
}

/**
 * Defines a terminal RGBA8888 color theme with 16 ANSI palette entries.
 */
@Serializable
data class ColorPalette(
    val id: String,
    val displayName: String,
    val foregroundRgba: UInt,
    val backgroundRgba: UInt,
    val cursorRgba: UInt,
    val selectionRgba: UInt,
    val ansi16Rgba: List<UInt>,
) {
    init {
        require(id.isNotBlank()) { "ColorPalette id must not be blank" }
        require(displayName.isNotBlank()) { "ColorPalette displayName must not be blank" }
        require(ansi16Rgba.size == ANSI_COLOR_COUNT) {
            "ColorPalette ansi16Rgba must contain $ANSI_COLOR_COUNT entries, found ${ansi16Rgba.size}"
        }
    }

    companion object {
        const val ANSI_COLOR_COUNT: Int = 16

        val DEFAULT_DARK = ColorPalette(
            id = "termvr-dark",
            displayName = "TermVR Dark",
            foregroundRgba = 0xE6EDF3FFu,
            backgroundRgba = 0x101418FFu,
            cursorRgba = 0x58A6FFFFu,
            selectionRgba = 0x264F78B3u,
            ansi16Rgba = listOf(
                0x1F242CFFu, // 0 Black
                0xFF7B72FFu, // 1 Red
                0x3FB950FFu, // 2 Green
                0xD29922FFu, // 3 Yellow
                0x58A6FFFFu, // 4 Blue
                0xBC8CFFFFu, // 5 Magenta
                0x39C5CFFFU, // 6 Cyan
                0xB1BAC4FFu, // 7 White
                0x6E7681FFu, // 8 Bright Black
                0xFFA198FFu, // 9 Bright Red
                0x56D364FFu, // 10 Bright Green
                0xE3B341FFu, // 11 Bright Yellow
                0x79C0FFFFu, // 12 Bright Blue
                0xD2A8FFFFu, // 13 Bright Magenta
                0x56D4DDFFu, // 14 Bright Cyan
                0xFFFFFFFFu, // 15 Bright White
            ),
        )

        val HIGH_CONTRAST_DARK = ColorPalette(
            id = "wcag-aaa-dark",
            displayName = "High Contrast Dark (WCAG AAA)",
            foregroundRgba = 0xFFFFFFFFu,
            backgroundRgba = 0x000000FFu,
            cursorRgba = 0xFFEA00FFu,
            selectionRgba = 0x3399FF99u,
            ansi16Rgba = listOf(
                0x000000FFu, // 0 Black
                0xFF8080FFu, // 1 Red (>= 8.6:1 on black)
                0x5AF78EFFu, // 2 Green (>= 14:1 on black)
                0xFFE066FFu, // 3 Yellow (>= 15:1 on black)
                0x8AB4F8FFu, // 4 Blue (>= 9.9:1 on black)
                0xFF8CE8FFu, // 5 Magenta (>= 10.1:1 on black)
                0x66F0FFFFu, // 6 Cyan (>= 14:1 on black)
                0xF2F2F2FFu, // 7 White (>= 18:1 on black)
                0xA6A6A6FFu, // 8 Bright Black (>= 8.6:1 on black)
                0xFF9E9EFFu, // 9 Bright Red
                0x80FFAAFFu, // 10 Bright Green
                0xFFF099FFu, // 11 Bright Yellow
                0xA8C7FAFFu, // 12 Bright Blue
                0xFFADFAFFu, // 13 Bright Magenta
                0x99F6FFFFu, // 14 Bright Cyan
                0xFFFFFFFFu, // 15 Bright White (21:1 on black)
            ),
        )

        val HIGH_CONTRAST_LIGHT = ColorPalette(
            id = "wcag-aaa-light",
            displayName = "High Contrast Light (WCAG AAA)",
            foregroundRgba = 0x000000FFu,
            backgroundRgba = 0xFFFFFFFFu,
            cursorRgba = 0x0033CCFFu,
            selectionRgba = 0xB3D7FF99u,
            ansi16Rgba = listOf(
                0x000000FFu, // 0 Black (21:1 on white)
                0x8C0000FFu, // 1 Red (>= 9.9:1 on white)
                0x00591CFFu, // 2 Green (>= 8.6:1 on white)
                0x5C4200FFu, // 3 Yellow ochre (>= 9.3:1 on white)
                0x002FA7FFu, // 4 Blue (>= 10.7:1 on white)
                0x6B006BFFu, // 5 Magenta (>= 11.4:1 on white)
                0x00525EFFu, // 6 Cyan (>= 8.9:1 on white)
                0xD9D9D9FFu, // 7 Light gray surface slot
                0x4D4D4DFFu, // 8 Bright Black (>= 8.4:1 on white)
                0xA30000FFu, // 9 Bright Red (>= 8.2:1 on white)
                0x006322FFu, // 10 Bright Green (>= 7.5:1 on white)
                0x694B00FFu, // 11 Bright Yellow (>= 8.1:1 on white)
                0x003CC7FFu, // 12 Bright Blue (>= 8.4:1 on white)
                0x800080FFu, // 13 Bright Magenta (>= 9.4:1 on white)
                0x005C6BFFu, // 14 Bright Cyan (>= 7.6:1 on white)
                0xFFFFFFFFu, // 15 Bright White slot
            ),
        )

        val DEUTERANOPIA_SAFE = ColorPalette(
            id = "deuteranopia-safe",
            displayName = "Deuteranopia Safe",
            foregroundRgba = 0xF0F4F8FFu,
            backgroundRgba = 0x0F1419FFu,
            cursorRgba = 0xF0E442FFu,
            selectionRgba = 0x0072B299u,
            ansi16Rgba = listOf(
                0x1B222CFFu, // 0 Black
                0xFF8C3BFFu, // 1 Vermilion orange (replaces confusable red)
                0x56B4E9FFu, // 2 Sky blue (replaces confusable green)
                0xF0E442FFu, // 3 High-luminance yellow
                0x4A90E2FFu, // 4 Blue
                0xCC79A7FFu, // 5 Reddish purple
                0x66D9E8FFu, // 6 Light cyan
                0xDCE3EAFFu, // 7 White
                0x7B8794FFu, // 8 Bright Black
                0xFFA96BFFu, // 9 Bright Vermilion
                0x7CC8F5FFu, // 10 Bright Sky Blue
                0xFFF078FFu, // 11 Bright Yellow
                0x74B0F7FFu, // 12 Bright Blue
                0xE29AC3FFu, // 13 Bright Purple
                0x99E9F2FFu, // 14 Bright Cyan
                0xFFFFFFFFu, // 15 Bright White
            ),
        )

        val BUILT_IN_PALETTES: List<ColorPalette> = listOf(
            DEFAULT_DARK,
            HIGH_CONTRAST_DARK,
            HIGH_CONTRAST_LIGHT,
            DEUTERANOPIA_SAFE,
        )

        fun byId(id: String): ColorPalette? = BUILT_IN_PALETTES.firstOrNull { it.id == id }

        fun contrastRatio(fgRgba: UInt, bgRgba: UInt): Double {
            val l1 = relativeLuminance(fgRgba)
            val l2 = relativeLuminance(bgRgba)
            return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
        }

        private fun relativeLuminance(rgba: UInt): Double {
            fun linearize(shift: Int): Double {
                val srgb = ((rgba shr shift) and 0xFFu).toDouble() / 255.0
                return if (srgb <= 0.04045) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * linearize(24) + 0.7152 * linearize(16) + 0.0722 * linearize(8)
        }
    }
}

/**
 * Describes a bundled multi-service container recipe such as Docker DinD or single-node k3s.
 */
@Serializable
data class RecipeManifest(
    val id: String,
    val title: String,
    val summary: String,
    val imageRef: String,
    val minHostRamMiB: Int,
    val defaultProfileRamMiB: Int,
    val minFreeDiskMiB: Int,
    val volumeTemplateSuffix: String,
    val volumeGuestMountPath: String,
    val initDaemonArgv: List<String>,
    val readinessTcpPort: Int,
    val optionalAssetManifest: String? = null,
    val defaultAutoCheckpoint: Boolean = false,
    val guestPrivileged: Boolean = true,
    val makeRootRshared: Boolean = false,
    val envVars: Map<String, String> = emptyMap(),
    val defaultPortForwards: List<PortForwardRule> = emptyList(),
    val optionalVariantMinProfileRamMiB: Int? = null,
    val optionalVariantInitDaemonArgv: List<String> = emptyList(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported RecipeManifest schemaVersion: $schemaVersion"
        }
        require(RECIPE_ID_REGEX.matches(id)) {
            "RecipeManifest id must match [a-z0-9-]{3,32}, found '$id'"
        }
        require(title.isNotBlank()) { "RecipeManifest title must not be blank" }
        require(summary.isNotBlank()) { "RecipeManifest summary must not be blank" }
        require(imageRef.isNotBlank() && !imageRef.any { it.isWhitespace() }) {
            "RecipeManifest imageRef must be a non-blank OCI reference without whitespace"
        }
        require(minHostRamMiB >= SandboxProfile.MIN_MEMORY_MIB) {
            "RecipeManifest minHostRamMiB must be at least ${SandboxProfile.MIN_MEMORY_MIB}"
        }
        require(defaultProfileRamMiB in SandboxProfile.MIN_MEMORY_MIB..SandboxProfile.MAX_MEMORY_MIB) {
            "RecipeManifest defaultProfileRamMiB must be within " +
                "${SandboxProfile.MIN_MEMORY_MIB}..${SandboxProfile.MAX_MEMORY_MIB}"
        }
        require(minFreeDiskMiB > 0) { "RecipeManifest minFreeDiskMiB must be positive" }
        require(VOLUME_SUFFIX_REGEX.matches(volumeTemplateSuffix)) {
            "RecipeManifest volumeTemplateSuffix must match [a-z0-9._-]+"
        }
        require(
            volumeGuestMountPath.startsWith("/") &&
                !volumeGuestMountPath.split('/').contains("..") &&
                !volumeGuestMountPath.contains(':') &&
                !volumeGuestMountPath.contains(','),
        ) {
            "RecipeManifest volumeGuestMountPath must be a clean absolute path"
        }
        require(initDaemonArgv.isNotEmpty() && initDaemonArgv.none { it.isEmpty() }) {
            "RecipeManifest initDaemonArgv must be non-empty without empty arguments"
        }
        require(readinessTcpPort in 1..65535) {
            "RecipeManifest readinessTcpPort must be within 1..65535, found $readinessTcpPort"
        }
        if (optionalAssetManifest != null) {
            require(optionalAssetManifest.isNotBlank() && !optionalAssetManifest.contains("..")) {
                "RecipeManifest optionalAssetManifest must be a safe relative asset path"
            }
        }
        if (optionalVariantMinProfileRamMiB != null) {
            require(optionalVariantMinProfileRamMiB in SandboxProfile.MIN_MEMORY_MIB..SandboxProfile.MAX_MEMORY_MIB) {
                "RecipeManifest optionalVariantMinProfileRamMiB out of bounds"
            }
        }
    }

    fun toSandboxProfile(
        profileId: String,
        displayName: String = title,
        enableOptionalVariant: Boolean = false,
        requestedMemoryMiB: Int? = null,
    ): SandboxProfile {
        val ramFloor = if (enableOptionalVariant && optionalVariantMinProfileRamMiB != null) {
            max(defaultProfileRamMiB, optionalVariantMinProfileRamMiB)
        } else {
            defaultProfileRamMiB
        }
        val effectiveRam = SandboxProfile.clampMemoryMiB(
            max(requestedMemoryMiB ?: ramFloor, ramFloor),
        )
        val daemonArgv = if (enableOptionalVariant && optionalVariantInitDaemonArgv.isNotEmpty()) {
            optionalVariantInitDaemonArgv
        } else {
            initDaemonArgv
        }
        val recipeVolume = VolumeMountSpec(
            sourceName = "$volumeTemplateSuffix-$profileId",
            guestMountPath = volumeGuestMountPath,
            kind = VolumeKind.NAMED_EXT4LITE,
            readOnly = false,
        )
        return SandboxProfile(
            id = profileId,
            displayName = displayName,
            imageRef = imageRef,
            muxMode = MuxMode.STDIO_MUX,
            initDaemonArgv = daemonArgv,
            memoryMiB = effectiveRam,
            guestPrivileged = guestPrivileged,
            volumes = listOf(recipeVolume),
            envVars = envVars,
            portForwards = defaultPortForwards,
            autoCheckpointOnLowMemory = defaultAutoCheckpoint,
            makeRootRshared = makeRootRshared,
        )
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        private val RECIPE_ID_REGEX = Regex("^[a-z0-9-]{3,32}$")
        private val VOLUME_SUFFIX_REGEX = Regex("^[a-z0-9._-]+$")
    }
}
