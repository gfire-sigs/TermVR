package dev.gfire.termvr.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalModelsTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `INV-T1 KeyBarConfig defaults match Section 14_1 two by seven slot layout and round-trip`() {
        // Property: default KeyBarConfig has schemaVersion 1 and exact 2x7 slots from Section 14.1.
        // Seam: KeyBarConfig constructor, DEFAULT_TOP_ROW, DEFAULT_BOTTOM_ROW, and Json codec.
        // Oracle: Design 0001 Section 11.2 and Section 14.1 slot table.
        // Fault detected: wrong slot count, swapped row order, or missing polymorphic discriminator.
        val config = KeyBarConfig()
        assertEquals(1, config.schemaVersion)
        assertTrue(config.visible)
        assertTrue(config.hapticFeedback)
        assertEquals(7, config.topRow.size)
        assertEquals(7, config.bottomRow.size)

        val topLabels = config.topRow.map { slotLabel(it) }
        val bottomLabels = config.bottomRow.map { slotLabel(it) }
        assertEquals(listOf("ESC", "/", "-", "HOME", "UP", "END", "PGUP"), topLabels)
        assertEquals(listOf("TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN"), bottomLabels)

        assertEquals(KeySlotSpec.Modifier(ModifierKey.CTRL), config.bottomRow[1])
        assertEquals(KeySlotSpec.Modifier(ModifierKey.ALT), config.bottomRow[2])

        val custom = config.copy(
            topRow = listOf(
                KeySlotSpec.GhosttyKey("ESCAPE", "ESC"),
                KeySlotSpec.LiteralText("|", "|"),
                KeySlotSpec.LiteralText("~", "~"),
                KeySlotSpec.CtrlChord('B', "C-b"),
                KeySlotSpec.Modifier(ModifierKey.SHIFT),
                KeySlotSpec.Modifier(ModifierKey.SUPER),
                KeySlotSpec.GhosttyKey("F1", "F1"),
            ),
        )
        val encoded = json.encodeToString(custom)
        assertEquals(custom, json.decodeFromString<KeyBarConfig>(encoded))
    }

    @Test
    fun `INV-T1 and INV-T2 KeyBarConfig and KeySlotSpec reject invalid schema versions slot counts and malformed slots`() {
        // Property: invalid schemaVersion, row length != 7, blank labels, or non-uppercase CtrlChord fail at init.
        // Seam: KeyBarConfig and KeySlotSpec constructors and Json.decodeFromString.
        // Oracle: Design 0001 Section 11.2 schemaVersion = 1 and 7-slot row contract.
        // Fault detected: silent acceptance of truncated rows, future schema versions, or lowercase chord chars.
        for (badVersion in listOf(-1, 0, 2, 99)) {
            assertThrows(IllegalArgumentException::class.java) {
                KeyBarConfig(schemaVersion = badVersion)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyBarConfig(topRow = KeyBarConfig.DEFAULT_TOP_ROW.drop(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyBarConfig(bottomRow = KeyBarConfig.DEFAULT_BOTTOM_ROW + KeySlotSpec.LiteralText("_", "_"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeySlotSpec.GhosttyKey("", "ESC")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeySlotSpec.GhosttyKey("ESCAPE", "   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeySlotSpec.LiteralText("", "/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeySlotSpec.CtrlChord('b', "C-b")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeySlotSpec.CtrlChord('1', "C-1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromString<KeyBarConfig>("""{"schemaVersion":2}""")
        }
    }

    @Test
    fun `INV-T2b ModifierLatchState contains all four Section 14_1 latch states`() {
        // Property: ModifierLatchState enumerates exact four states from Section 11.2 and 14.1.
        // Seam: ModifierLatchState.entries and ModifierKey.entries.
        // Oracle: Design 0001 Section 11.2 enum declarations.
        // Fault detected: missing HELD or ONE_SHOT state in modifier state machine.
        assertEquals(
            listOf("RELEASED", "ONE_SHOT", "LOCKED", "HELD"),
            ModifierLatchState.entries.map { it.name },
        )
        assertEquals(
            listOf("CTRL", "ALT", "SHIFT", "SUPER"),
            ModifierKey.entries.map { it.name },
        )
    }

    @Test
    fun `INV-T3 FontConfig validates bounds clamps half-sp steps and verifies custom font SHA-256 format`() {
        // Property: FontConfig enforces sizeSp in 8.0..32.0 and cellHeightMultiplier in 1.0..1.6, and clamps in 0.5 steps.
        // Seam: FontConfig constructor, clampSizeSp, clampCellHeightMultiplier, clamped, and Json codec.
        // Oracle: Design 0001 Section 11.2 and Section 13.3 bounds.
        // Fault detected: off-by-one float bound, NaN bypass, unrounded pinch-zoom step, or invalid SHA-256 hex.
        val defaultFont = FontConfig()
        assertEquals(1, defaultFont.schemaVersion)
        assertEquals("jetbrains-mono", defaultFont.familyId)
        assertEquals(13.0f, defaultFont.sizeSp)
        assertEquals(1.15f, defaultFont.cellHeightMultiplier)
        assertTrue(defaultFont.ligaturesEnabled)
        assertTrue(defaultFont.nerdFontSymbolsEnabled)

        assertEquals(8.0f, FontConfig.clampSizeSp(3.2f))
        assertEquals(32.0f, FontConfig.clampSizeSp(48.0f))
        assertEquals(13.5f, FontConfig.clampSizeSp(13.49f))
        assertEquals(14.0f, FontConfig.clampSizeSp(13.76f))
        assertEquals(1.0f, FontConfig.clampCellHeightMultiplier(0.5f))
        assertEquals(1.6f, FontConfig.clampCellHeightMultiplier(2.2f))

        val clamped = FontConfig.clamped(sizeSp = 4.0f, cellHeightMultiplier = 2.0f)
        assertEquals(8.0f, clamped.sizeSp)
        assertEquals(1.6f, clamped.cellHeightMultiplier)

        for (badSize in listOf(7.99f, 32.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                FontConfig(sizeSp = badSize)
            }
        }
        for (badMult in listOf(0.99f, 1.61f, Float.NaN)) {
            assertThrows(IllegalArgumentException::class.java) {
                FontConfig(cellHeightMultiplier = badMult)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            FontConfig(schemaVersion = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FontConfig(customFontPath = "/data/user/0/dev.gfire.termvr/files/fonts/bad.ttf", customFontSha256 = "not-a-sha256")
        }

        val validSha = "a".repeat(64)
        val withCustomFont = FontConfig(
            customFontPath = "/data/user/0/dev.gfire.termvr/files/fonts/$validSha.ttf",
            customFontSha256 = validSha,
            sizeSp = 32.0f,
            cellHeightMultiplier = 1.6f,
        )
        assertEquals(withCustomFont, json.decodeFromString<FontConfig>(json.encodeToString(withCustomFont)))
    }

    @Test
    fun `INV-T4 ColorPalette enforces 16 ANSI slots and built-in WCAG AAA and deuteranopia invariants`() {
        // Property: ColorPalette requires 16 RGBA8888 entries; WCAG AAA dark/light palettes exceed 7.0:1 contrast.
        // Seam: ColorPalette constructor, BUILT_IN_PALETTES, and contrastRatio oracle.
        // Oracle: WCAG 2.1 relative luminance formula (>= 7.0:1 for AAA) and Design 0001 Section 8.3 / 11.2.
        // Fault detected: 15-color or 17-color ANSI table, insufficient AAA contrast, or indistinguishable red/green.
        assertThrows(IllegalArgumentException::class.java) {
            ColorPalette(
                id = "short",
                displayName = "Short",
                foregroundRgba = 0xFFFFFFFFu,
                backgroundRgba = 0x000000FFu,
                cursorRgba = 0xFFFFFFFFu,
                selectionRgba = 0x444444FFu,
                ansi16Rgba = List(15) { 0xFFFFFFFFu },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ColorPalette(
                id = "long",
                displayName = "Long",
                foregroundRgba = 0xFFFFFFFFu,
                backgroundRgba = 0x000000FFu,
                cursorRgba = 0xFFFFFFFFu,
                selectionRgba = 0x444444FFu,
                ansi16Rgba = List(17) { 0xFFFFFFFFu },
            )
        }

        val builtIns = ColorPalette.BUILT_IN_PALETTES
        assertEquals(4, builtIns.size)
        for (palette in builtIns) {
            assertEquals(16, palette.ansi16Rgba.size)
            val roundTripped = json.decodeFromString<ColorPalette>(json.encodeToString(palette))
            assertEquals(palette, roundTripped)
        }

        val hcDark = ColorPalette.HIGH_CONTRAST_DARK
        assertTrue(wcagContrast(hcDark.foregroundRgba, hcDark.backgroundRgba) >= 7.0)
        for (idx in 1..15) {
            assertTrue(
                wcagContrast(hcDark.ansi16Rgba[idx], hcDark.backgroundRgba) >= 7.0,
                "Expected HIGH_CONTRAST_DARK ANSI[$idx] contrast >= 7.0",
            )
        }

        val hcLight = ColorPalette.HIGH_CONTRAST_LIGHT
        assertTrue(wcagContrast(hcLight.foregroundRgba, hcLight.backgroundRgba) >= 7.0)
        for (idx in listOf(0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14)) {
            assertTrue(
                wcagContrast(hcLight.ansi16Rgba[idx], hcLight.backgroundRgba) >= 7.0,
                "Expected HIGH_CONTRAST_LIGHT ANSI[$idx] contrast >= 7.0",
            )
        }

        val deut = ColorPalette.DEUTERANOPIA_SAFE
        assertTrue(wcagContrast(deut.foregroundRgba, deut.backgroundRgba) >= 7.0)
        assertNotEquals(deut.ansi16Rgba[1].toLong(), deut.ansi16Rgba[2].toLong())
        val ansiRedBlueChannel = ((deut.ansi16Rgba[1] shr 8) and 0xFFu).toInt()
        val ansiGreenBlueChannel = ((deut.ansi16Rgba[2] shr 8) and 0xFFu).toInt()
        assertTrue(
            kotlin.math.abs(ansiRedBlueChannel - ansiGreenBlueChannel) >= 80,
            "Deuteranopia-safe ANSI red and green must separate along the blue axis",
        )
    }

    @Test
    fun `INV-T5 RecipeManifest validates resource floors ports and instantiates compliant SandboxProfile`() {
        // Property: RecipeManifest rejects invalid RAM/port/mount values and builds per-profile volumes accurately.
        // Seam: RecipeManifest constructor, Json codec, and toSandboxProfile.
        // Oracle: Design 0001 Section 11.2 and Section 16.2 recipe specifications.
        // Fault detected: missing profileId suffix on volume, unprivileged recipe profile, or invalid readiness port.
        val k3s = RecipeManifest(
            id = "k3s-single-node",
            title = "Kubernetes Single-Node (k3s)",
            summary = "Local single-node k3s cluster.",
            imageRef = "rancher/k3s:v1.32.2-k3s1",
            minHostRamMiB = 6144,
            defaultProfileRamMiB = 2560,
            minFreeDiskMiB = 3072,
            volumeTemplateSuffix = "k3s",
            volumeGuestMountPath = "/var/lib/rancher/k3s",
            initDaemonArgv = listOf("/bin/k3s", "server", "--flannel-backend=host-gw"),
            readinessTcpPort = 6443,
            optionalAssetManifest = "recipes/calico-vxlan.yaml",
            defaultAutoCheckpoint = false,
            makeRootRshared = true,
            optionalVariantMinProfileRamMiB = 4096,
            optionalVariantInitDaemonArgv = listOf("/bin/k3s", "server", "--flannel-backend=none", "--disable-network-policy"),
        )
        assertEquals(k3s, json.decodeFromString<RecipeManifest>(json.encodeToString(k3s)))

        val profile = k3s.toSandboxProfile(profileId = "dev-k3s", enableOptionalVariant = true)
        assertEquals(4096, profile.memoryMiB)
        assertTrue(profile.guestPrivileged)
        assertTrue(profile.makeRootRshared)
        assertEquals(false, profile.autoCheckpointOnLowMemory)
        assertTrue(profile.volumes.any { it.sourceName == "k3s-dev-k3s" && it.guestMountPath == "/var/lib/rancher/k3s" })
        assertTrue(profile.initDaemonArgv.contains("--flannel-backend=none"))

        assertThrows(IllegalArgumentException::class.java) {
            k3s.copy(readinessTcpPort = 70000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            k3s.copy(volumeGuestMountPath = "relative/path")
        }
        assertThrows(IllegalArgumentException::class.java) {
            k3s.copy(initDaemonArgv = emptyList())
        }
    }

    private fun slotLabel(slot: KeySlotSpec): String = when (slot) {
        is KeySlotSpec.Modifier -> slot.mod.name
        is KeySlotSpec.GhosttyKey -> slot.label
        is KeySlotSpec.LiteralText -> slot.label
        is KeySlotSpec.CtrlChord -> slot.label
    }

    private fun wcagContrast(fgRgba: UInt, bgRgba: UInt): Double {
        val l1 = relativeLuminance(fgRgba)
        val l2 = relativeLuminance(bgRgba)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(rgba: UInt): Double {
        fun channel(shift: Int): Double {
            val s = ((rgba shr shift) and 0xFFu).toDouble() / 255.0
            return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(24) + 0.7152 * channel(16) + 0.0722 * channel(8)
    }
}
