package dev.gfire.termvr.ui.theme

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

object TermVrColors {
    val DarkBackground = Color(0xFF0D1117)
    val DarkSurface = Color(0xFF161B22)
    val DarkSurfaceVariant = Color(0xFF21262D)
    val DarkOutline = Color(0xFF30363D)
    val DarkPrimary = Color(0xFF58A6FF)
    val DarkSecondary = Color(0xFF3FB950)
    val DarkWarning = Color(0xFFD29922)
    val DarkError = Color(0xFFF85149)
    val DarkOnSurface = Color(0xFFE6EDF3)
    val DarkOnSurfaceMuted = Color(0xFF8B949E)

    val LightBackground = Color(0xFFF6F8FA)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceVariant = Color(0xFFEAECEF)
    val LightOutline = Color(0xFFD0D7DE)
    val LightPrimary = Color(0xFF0969DA)
    val LightSecondary = Color(0xFF1A7F37)
    val LightError = Color(0xFFCF222E)
    val LightOnSurface = Color(0xFF1F2328)
}
