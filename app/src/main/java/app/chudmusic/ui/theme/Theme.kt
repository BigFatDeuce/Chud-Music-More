package app.chudmusic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.chudmusic.R

/**
 * The two skins, with the PC app's colour tokens (src/renderer/src/styles/skins.css):
 * - Chud Nocturne: ink blue-black ramp, a single lime LCD accent, phosphor LCD;
 * - Platine: mid-light grey (not white), near-black text, cobalt accent, grey-green reflective LCD.
 * Same names as the CSS variables, so a change on the PC side is easy to carry over.
 */
@Immutable
data class ChudColors(
    val isDark: Boolean,
    val bg0: Color, val bg1: Color, val bg2: Color, val bg3: Color, val bg4: Color,
    val line: Color, val lineStrong: Color,
    val text0: Color, val text1: Color, val text2: Color,
    val accent: Color, val accentInk: Color, val accentSoft: Color, val accent2: Color,
    val danger: Color,
    val lcdBg: Color, val lcdFg: Color, val lcdDim: Color, val lcdGlow: Color,
)

enum class Skin { NOCTURNE, PLATINE }

val Nocturne = ChudColors(
    isDark = true,
    bg0 = Color(0xFF07080D), bg1 = Color(0xFF0B0D14), bg2 = Color(0xFF11141C), bg3 = Color(0xFF1A1E28), bg4 = Color(0xFF252A36),
    line = Color(0xFFEEF0F5).copy(alpha = 0.07f), lineStrong = Color(0xFFEEF0F5).copy(alpha = 0.12f),
    text0 = Color(0xFFEEF0F5), text1 = Color(0xFFA3A9B8), text2 = Color(0xFF7A8296),
    accent = Color(0xFFC6FF2E), accentInk = Color(0xFF0C1200), accentSoft = Color(0xFFC6FF2E).copy(alpha = 0.14f), accent2 = Color(0xFFFF3FA4),
    danger = Color(0xFFE5484D),
    lcdBg = Color(0xFF070B04), lcdFg = Color(0xFFC6FF2E), lcdDim = Color(0xFFC6FF2E).copy(alpha = 0.22f), lcdGlow = Color(0xFFC6FF2E).copy(alpha = 0.6f),
)

val Platine = ChudColors(
    isDark = false,
    bg0 = Color(0xFFA2A7B0), bg1 = Color(0xFFB4B8C0), bg2 = Color(0xFFAAAFB7), bg3 = Color(0xFF9EA3AC), bg4 = Color(0xFF8F959F),
    line = Color(0xFF141821).copy(alpha = 0.13f), lineStrong = Color(0xFF141821).copy(alpha = 0.24f),
    text0 = Color(0xFF0D0F12), text1 = Color(0xFF24292F), text2 = Color(0xFF363B43),
    accent = Color(0xFF103A80), accentInk = Color(0xFFFFFFFF), accentSoft = Color(0xFF103A80).copy(alpha = 0.16f), accent2 = Color(0xFFC93A68),
    danger = Color(0xFFC32F36),
    lcdBg = Color(0xFF9AA688), lcdFg = Color(0xFF1B2513), lcdDim = Color(0xFF1B2513).copy(alpha = 0.13f), lcdGlow = Color.Transparent,
)

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(R.font.inter, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

@OptIn(ExperimentalTextApi::class)
private fun grotesk(weight: Int) = Font(R.font.space_grotesk, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val InterFamily = FontFamily(inter(400), inter(500), inter(600), inter(700))
val DisplayFamily = FontFamily(grotesk(400), grotesk(500), grotesk(600), grotesk(700))
val LcdFamily = FontFamily(Font(R.font.vt323))

val LocalChud = staticCompositionLocalOf { Nocturne }

/** Shortcut: `Chud.colors.accent`. */
object Chud {
    val colors: ChudColors @Composable get() = LocalChud.current
}

object Radii {
    val xl = 18.dp
    val l = 14.dp
    val m = 10.dp
    val s = 7.dp
}

private fun typography(c: ChudColors, displayWeight: Int) = Typography(
    displaySmall = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(displayWeight), fontSize = 30.sp, letterSpacing = (-0.02).em, color = c.text0),
    headlineMedium = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(displayWeight), fontSize = 26.sp, letterSpacing = (-0.02).em),
    headlineSmall = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(displayWeight), fontSize = 22.sp, letterSpacing = (-0.02).em),
    titleLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(displayWeight), fontSize = 20.sp, letterSpacing = (-0.01).em),
    titleMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

@Composable
fun ChudTheme(skin: Skin?, content: @Composable () -> Unit) {
    val c = when (skin) {
        Skin.NOCTURNE -> Nocturne
        Skin.PLATINE -> Platine
        null -> if (isSystemInDarkTheme()) Nocturne else Platine
    }
    // Material components get the skin's colours: no dynamic "Material You" colours, they would erase the skin.
    val scheme = if (c.isDark) darkColorScheme(
        primary = c.accent, onPrimary = c.accentInk, primaryContainer = c.accentSoft, onPrimaryContainer = c.text0,
        secondary = c.text1, onSecondary = c.bg0, secondaryContainer = c.bg3, onSecondaryContainer = c.text0,
        tertiary = c.accent2, background = c.bg0, onBackground = c.text0, surface = c.bg1, onSurface = c.text0,
        surfaceVariant = c.bg2, onSurfaceVariant = c.text1, surfaceContainerLowest = c.bg0, surfaceContainerLow = c.bg1,
        surfaceContainer = c.bg2, surfaceContainerHigh = c.bg3, surfaceContainerHighest = c.bg4,
        surfaceBright = c.bg4, surfaceDim = c.bg0,
        outline = c.lineStrong, outlineVariant = c.line, error = c.danger, onError = Color.White, inverseSurface = c.text0,
        inverseOnSurface = c.bg0, inversePrimary = c.bg0, scrim = Color.Black.copy(alpha = 0.6f),
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.accentInk, primaryContainer = c.accentSoft, onPrimaryContainer = c.text0,
        secondary = c.text1, onSecondary = c.bg1, secondaryContainer = c.bg3, onSecondaryContainer = c.text0,
        tertiary = c.accent2, background = c.bg0, onBackground = c.text0, surface = c.bg1, onSurface = c.text0,
        surfaceVariant = c.bg2, onSurfaceVariant = c.text1, surfaceContainerLowest = c.bg1, surfaceContainerLow = c.bg1,
        surfaceContainer = c.bg2, surfaceContainerHigh = c.bg3, surfaceContainerHighest = c.bg4,
        surfaceBright = c.bg1, surfaceDim = c.bg0,
        outline = c.lineStrong, outlineVariant = c.line, error = c.danger, onError = Color.White, inverseSurface = c.text0,
        inverseOnSurface = c.bg1, inversePrimary = c.bg1, scrim = Color.Black.copy(alpha = 0.45f),
    )
    CompositionLocalProvider(LocalChud provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography(c, if (c.isDark) 500 else 600), content = content)
    }
}
