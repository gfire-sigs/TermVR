package dev.gfire.termvr.ui.theme

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private const val BUNDLED_MONO_ASSET_PATH = "fonts/TermVRMonoNF-Regular.ttf"

val LocalTermVrMonoFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

/**
 * Loads `TermVRMonoNF-Regular.ttf` from app/library assets, falling back safely to [FontFamily.Monospace].
 */
fun loadTermVrMonoFontFamily(context: Context): FontFamily = runCatching {
    val assets = context.assets ?: return FontFamily.Monospace
    assets.open(BUNDLED_MONO_ASSET_PATH).use { /* verify asset exists before constructing Font */ }
    FontFamily(
        Font(
            path = BUNDLED_MONO_ASSET_PATH,
            assetManager = assets,
            weight = FontWeight.Normal,
        ),
    )
}.getOrDefault(FontFamily.Monospace)

fun termVrTypography(monoFontFamily: FontFamily = FontFamily.Monospace): Typography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = monoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = monoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

val TermVrTypography = termVrTypography()
