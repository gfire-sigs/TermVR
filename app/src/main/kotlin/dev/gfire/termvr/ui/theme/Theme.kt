package dev.gfire.termvr.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.gfire.termvr.core.model.ColorPalette

@Composable
fun TermVrTheme(
    palette: ColorPalette = ColorPalette.DEFAULT_DARK,
    content: @Composable () -> Unit,
) {
    val isDark = ColorPalette.contrastRatio(palette.backgroundRgba, 0x000000FFu) <
        ColorPalette.contrastRatio(palette.backgroundRgba, 0xFFFFFFFFu)
    val scheme = if (isDark) {
        darkColorScheme(
            primary = palette.toComposeCursor(),
            onPrimary = Color.Black,
            secondary = TermVrColors.DarkSecondary,
            onSecondary = Color.Black,
            background = palette.toComposeBackground(),
            onBackground = palette.toComposeForeground(),
            surface = TermVrColors.DarkSurface,
            onSurface = palette.toComposeForeground(),
            surfaceVariant = TermVrColors.DarkSurfaceVariant,
            onSurfaceVariant = TermVrColors.DarkOnSurfaceMuted,
            outline = TermVrColors.DarkOutline,
            error = TermVrColors.DarkError,
            onError = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = palette.toComposeCursor(),
            onPrimary = Color.White,
            secondary = TermVrColors.LightSecondary,
            onSecondary = Color.White,
            background = palette.toComposeBackground(),
            onBackground = palette.toComposeForeground(),
            surface = TermVrColors.LightSurface,
            onSurface = palette.toComposeForeground(),
            surfaceVariant = TermVrColors.LightSurfaceVariant,
            onSurfaceVariant = TermVrColors.LightOnSurface,
            outline = TermVrColors.LightOutline,
            error = TermVrColors.LightError,
            onError = Color.White,
        )
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = TermVrTypography,
        content = content,
    )
}
