package dev.gfire.termvr.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.gfire.termvr.core.model.ColorPalette

val LocalTermVrPaletteTokens = staticCompositionLocalOf {
    resolvePaletteTokens(ColorPalette.DEFAULT_DARK)
}

val TermVrShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(10.dp),
)

@Composable
fun TermVrTheme(
    palette: ColorPalette = ColorPalette.DEFAULT_DARK,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val monoFontFamily = remember(context) { loadTermVrMonoFontFamily(context) }
    val typography = remember(monoFontFamily) { termVrTypography(monoFontFamily) }
    val scheme = remember(palette) { colorSchemeFromPalette(palette) }
    val tokens = remember(palette) { resolvePaletteTokens(palette) }

    CompositionLocalProvider(
        LocalTermVrPaletteTokens provides tokens,
        LocalTermVrMonoFontFamily provides monoFontFamily,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = TermVrShapes,
            typography = typography,
            content = content,
        )
    }
}
