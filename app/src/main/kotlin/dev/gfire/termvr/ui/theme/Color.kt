package dev.gfire.termvr.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.gfire.termvr.core.model.ColorPalette

internal fun rgbaToComposeColor(rgba: UInt): Color {
    val raw = rgba.toLong() and 0xFFFFFFFFL
    val r = ((raw ushr 24) and 0xFFL).toInt()
    val g = ((raw ushr 16) and 0xFFL).toInt()
    val b = ((raw ushr 8) and 0xFFL).toInt()
    val a = (raw and 0xFFL).toInt()
    return Color(red = r, green = g, blue = b, alpha = a)
}

internal fun ColorPalette.toComposeBackground(): Color = rgbaToComposeColor(backgroundRgba)
internal fun ColorPalette.toComposeForeground(): Color = rgbaToComposeColor(foregroundRgba)
internal fun ColorPalette.toComposeCursor(): Color = rgbaToComposeColor(cursorRgba)

/**
 * Semantic palette tokens shared across all TermVR surfaces, badges, and status indicators.
 */
@Immutable
data class TermVrPaletteTokens(
    val isDark: Boolean,
    val navBarBackground: Color,
    val statusRunning: Color,
    val statusFrozen: Color,
    val statusStopped: Color,
    val statusError: Color,
    val badgeBorder: Color,
)

internal object TermVrObsidianColors {
    val Background = Color(0xFF0D1011)
    val NavBar = Color(0xFF101517)
    val Surface = Color(0xFF14191B)
    val SurfaceContainer = Color(0xFF1A2124)
    val SurfaceContainerHigh = Color(0xFF20292D)
    val SurfaceContainerHighest = Color(0xFF263136)
    val Outline = Color(0xFF263136)
    val OutlineVariant = Color(0xFF1E272B)

    val Primary = Color(0xFFF06E1E)
    val OnPrimary = Color(0xFF0D1011)
    val PrimaryContainer = Color(0xFF2C190E)
    val OnPrimaryContainer = Color(0xFFFFB385)

    val Secondary = Color(0xFF1DC99A)
    val OnSecondary = Color(0xFF052119)
    val SecondaryContainer = Color(0xFF0F2B23)
    val OnSecondaryContainer = Color(0xFF7CE8C8)

    val Tertiary = Color(0xFF45D0E0)
    val OnTertiary = Color(0xFF052124)
    val TertiaryContainer = Color(0xFF0E2A2E)
    val OnTertiaryContainer = Color(0xFF8AE7F2)

    val OnBackground = Color(0xFFE8EEF0)
    val OnSurface = Color(0xFFE8EEF0)
    val OnSurfaceVariant = Color(0xFF8FA0A6)

    val Error = Color(0xFFF06469)
    val OnError = Color(0xFF1D0608)
    val ErrorContainer = Color(0xFF331215)
    val OnErrorContainer = Color(0xFFFFB3B5)
}

internal fun resolvePaletteTokens(palette: ColorPalette): TermVrPaletteTokens = when (palette.id) {
    ColorPalette.DEFAULT_DARK.id -> TermVrPaletteTokens(
        isDark = true,
        navBarBackground = TermVrObsidianColors.NavBar,
        statusRunning = TermVrObsidianColors.Secondary,
        statusFrozen = TermVrObsidianColors.Primary,
        statusStopped = TermVrObsidianColors.OnSurfaceVariant,
        statusError = TermVrObsidianColors.Error,
        badgeBorder = TermVrObsidianColors.Outline,
    )

    ColorPalette.HIGH_CONTRAST_DARK.id -> TermVrPaletteTokens(
        isDark = true,
        navBarBackground = Color(0xFF050505),
        statusRunning = Color(0xFF5AF78E),
        statusFrozen = Color(0xFFFFEA00),
        statusStopped = Color(0xFFA6A6A6),
        statusError = Color(0xFFFF8080),
        badgeBorder = Color(0xFF4D4D4D),
    )

    ColorPalette.HIGH_CONTRAST_LIGHT.id -> TermVrPaletteTokens(
        isDark = false,
        navBarBackground = Color(0xFFF0F3F6),
        statusRunning = Color(0xFF00591C),
        statusFrozen = Color(0xFF0033CC),
        statusStopped = Color(0xFF4D4D4D),
        statusError = Color(0xFF8C0000),
        badgeBorder = Color(0xFF8C959F),
    )

    ColorPalette.DEUTERANOPIA_SAFE.id -> TermVrPaletteTokens(
        isDark = true,
        navBarBackground = Color(0xFF121820),
        statusRunning = Color(0xFF56B4E9),
        statusFrozen = Color(0xFFF0E442),
        statusStopped = Color(0xFF7B8794),
        statusError = Color(0xFFFF8C3B),
        badgeBorder = Color(0xFF2C3947),
    )

    else -> {
        val isDark = ColorPalette.contrastRatio(palette.backgroundRgba, 0x000000FFu) <
            ColorPalette.contrastRatio(palette.backgroundRgba, 0xFFFFFFFFu)
        TermVrPaletteTokens(
            isDark = isDark,
            navBarBackground = if (isDark) TermVrObsidianColors.NavBar else Color(0xFFF0F3F6),
            statusRunning = rgbaToComposeColor(palette.ansi16Rgba[2]),
            statusFrozen = palette.toComposeCursor(),
            statusStopped = rgbaToComposeColor(palette.ansi16Rgba[8]),
            statusError = rgbaToComposeColor(palette.ansi16Rgba[1]),
            badgeBorder = if (isDark) TermVrObsidianColors.Outline else Color(0xFFB8C2CC),
        )
    }
}

internal fun colorSchemeFromPalette(palette: ColorPalette): ColorScheme = when (palette.id) {
    ColorPalette.DEFAULT_DARK.id -> darkColorScheme(
        primary = TermVrObsidianColors.Primary,
        onPrimary = TermVrObsidianColors.OnPrimary,
        primaryContainer = TermVrObsidianColors.PrimaryContainer,
        onPrimaryContainer = TermVrObsidianColors.OnPrimaryContainer,
        secondary = TermVrObsidianColors.Secondary,
        onSecondary = TermVrObsidianColors.OnSecondary,
        secondaryContainer = TermVrObsidianColors.SecondaryContainer,
        onSecondaryContainer = TermVrObsidianColors.OnSecondaryContainer,
        tertiary = TermVrObsidianColors.Tertiary,
        onTertiary = TermVrObsidianColors.OnTertiary,
        tertiaryContainer = TermVrObsidianColors.TertiaryContainer,
        onTertiaryContainer = TermVrObsidianColors.OnTertiaryContainer,
        background = TermVrObsidianColors.Background,
        onBackground = TermVrObsidianColors.OnBackground,
        surface = TermVrObsidianColors.Surface,
        onSurface = TermVrObsidianColors.OnSurface,
        surfaceVariant = TermVrObsidianColors.SurfaceContainer,
        onSurfaceVariant = TermVrObsidianColors.OnSurfaceVariant,
        surfaceContainerLowest = TermVrObsidianColors.Background,
        surfaceContainerLow = TermVrObsidianColors.Surface,
        surfaceContainer = TermVrObsidianColors.SurfaceContainer,
        surfaceContainerHigh = TermVrObsidianColors.SurfaceContainerHigh,
        surfaceContainerHighest = TermVrObsidianColors.SurfaceContainerHighest,
        outline = TermVrObsidianColors.Outline,
        outlineVariant = TermVrObsidianColors.OutlineVariant,
        error = TermVrObsidianColors.Error,
        onError = TermVrObsidianColors.OnError,
        errorContainer = TermVrObsidianColors.ErrorContainer,
        onErrorContainer = TermVrObsidianColors.OnErrorContainer,
    )

    ColorPalette.HIGH_CONTRAST_DARK.id -> darkColorScheme(
        primary = Color(0xFFFFEA00),
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF332E00),
        onPrimaryContainer = Color(0xFFFFF380),
        secondary = Color(0xFF5AF78E),
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF0A3318),
        onSecondaryContainer = Color(0xFF99FFBA),
        tertiary = Color(0xFF66F0FF),
        onTertiary = Color.Black,
        tertiaryContainer = Color(0xFF003338),
        onTertiaryContainer = Color(0xFFB3F8FF),
        background = Color.Black,
        onBackground = Color.White,
        surface = Color(0xFF0A0A0A),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF161616),
        onSurfaceVariant = Color(0xFFD0D0D0),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0A0A0A),
        surfaceContainer = Color(0xFF141414),
        surfaceContainerHigh = Color(0xFF1E1E1E),
        surfaceContainerHighest = Color(0xFF282828),
        outline = Color(0xFF4D4D4D),
        outlineVariant = Color(0xFF2E2E2E),
        error = Color(0xFFFF8080),
        onError = Color.Black,
        errorContainer = Color(0xFF400A0A),
        onErrorContainer = Color(0xFFFFB8B8),
    )

    ColorPalette.HIGH_CONTRAST_LIGHT.id -> lightColorScheme(
        primary = Color(0xFF0033CC),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDDE6FF),
        onPrimaryContainer = Color(0xFF001B6B),
        secondary = Color(0xFF00591C),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD6F5DF),
        onSecondaryContainer = Color(0xFF00290B),
        tertiary = Color(0xFF00525E),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFCFF3F8),
        onTertiaryContainer = Color(0xFF002429),
        background = Color.White,
        onBackground = Color.Black,
        surface = Color(0xFFF6F8FA),
        onSurface = Color.Black,
        surfaceVariant = Color(0xFFEAECEF),
        onSurfaceVariant = Color(0xFF333A40),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF6F8FA),
        surfaceContainer = Color(0xFFEDF1F5),
        surfaceContainerHigh = Color(0xFFE3E8EE),
        surfaceContainerHighest = Color(0xFFD9E0E8),
        outline = Color(0xFF8C959F),
        outlineVariant = Color(0xFFC8D0D8),
        error = Color(0xFF8C0000),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    )

    ColorPalette.DEUTERANOPIA_SAFE.id -> darkColorScheme(
        primary = Color(0xFFF0E442),
        onPrimary = Color(0xFF0F1419),
        primaryContainer = Color(0xFF302D0A),
        onPrimaryContainer = Color(0xFFFAF38E),
        secondary = Color(0xFF56B4E9),
        onSecondary = Color(0xFF071D2B),
        secondaryContainer = Color(0xFF0E2B3D),
        onSecondaryContainer = Color(0xFF9ED9FA),
        tertiary = Color(0xFF66D9E8),
        onTertiary = Color(0xFF062126),
        tertiaryContainer = Color(0xFF0E2D33),
        onTertiaryContainer = Color(0xFFB3F2FA),
        background = Color(0xFF0F1419),
        onBackground = Color(0xFFF0F4F8),
        surface = Color(0xFF151C23),
        onSurface = Color(0xFFF0F4F8),
        surfaceVariant = Color(0xFF1C2630),
        onSurfaceVariant = Color(0xFF98A6B3),
        surfaceContainerLowest = Color(0xFF0B0F13),
        surfaceContainerLow = Color(0xFF151C23),
        surfaceContainer = Color(0xFF1C2630),
        surfaceContainerHigh = Color(0xFF232F3B),
        surfaceContainerHighest = Color(0xFF2C3947),
        outline = Color(0xFF2C3947),
        outlineVariant = Color(0xFF222D38),
        error = Color(0xFFFF8C3B),
        onError = Color(0xFF1F0E00),
        errorContainer = Color(0xFF381C08),
        onErrorContainer = Color(0xFFFFC299),
    )

    else -> {
        val bg = palette.toComposeBackground()
        val fg = palette.toComposeForeground()
        val primary = palette.toComposeCursor()
        val secondary = rgbaToComposeColor(palette.ansi16Rgba[2])
        val isDark = ColorPalette.contrastRatio(palette.backgroundRgba, 0x000000FFu) <
            ColorPalette.contrastRatio(palette.backgroundRgba, 0xFFFFFFFFu)
        if (isDark) {
            darkColorScheme(
                primary = primary,
                onPrimary = Color.Black,
                secondary = secondary,
                onSecondary = Color.Black,
                background = bg,
                onBackground = fg,
                surface = TermVrObsidianColors.Surface,
                onSurface = fg,
                surfaceVariant = TermVrObsidianColors.SurfaceContainer,
                onSurfaceVariant = TermVrObsidianColors.OnSurfaceVariant,
                outline = TermVrObsidianColors.Outline,
                outlineVariant = TermVrObsidianColors.OutlineVariant,
                error = TermVrObsidianColors.Error,
                onError = Color.Black,
            )
        } else {
            lightColorScheme(
                primary = primary,
                onPrimary = Color.White,
                secondary = secondary,
                onSecondary = Color.White,
                background = bg,
                onBackground = fg,
                surface = Color(0xFFF6F8FA),
                onSurface = fg,
                surfaceVariant = Color(0xFFEAECEF),
                onSurfaceVariant = Color(0xFF333A40),
                outline = Color(0xFF8C959F),
                outlineVariant = Color(0xFFC8D0D8),
                error = Color(0xFF8C0000),
                onError = Color.White,
            )
        }
    }
}
