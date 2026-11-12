package app.neifur.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.neifur.R

/** Neifur palette: near-black ink with a cold violet tint, one violet accent, mint for "ready to play". */
object Nf {
    val Ink = Color(0xFF08080C)
    val Surface = Color(0xFF111119)
    val SurfaceHi = Color(0xFF1A1A25)
    val Line = Color(0xFF282836)
    val Text = Color(0xFFF3F1FA)
    val TextDim = Color(0xFFA9A6BC)
    val TextMute = Color(0xFF6F6C84)
    val Violet = Color(0xFF9B7BFF)
    val Pink = Color(0xFFFF6B9A)
    val Mint = Color(0xFF5CF2B0)
    val Amber = Color(0xFFFFC56B)
    val Red = Color(0xFFFF6B6B)
}

/** The accent of the current screen; a title page tints it with the poster's colour. */
val LocalAccent = compositionLocalOf { Nf.Violet }

@OptIn(ExperimentalTextApi::class)
private fun vf(res: Int, w: Int) =
    Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Manrope = FontFamily(vf(R.font.manrope, 400), vf(R.font.manrope, 500), vf(R.font.manrope, 600), vf(R.font.manrope, 700), vf(R.font.manrope, 800))
val Unbounded = FontFamily(vf(R.font.unbounded, 500), vf(R.font.unbounded, 600), vf(R.font.unbounded, 700))

object Type {
    val Hero = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight(600), fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.01).em)
    val Display = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight(600), fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-0.01).em)
    val Section = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight(500), fontSize = 15.sp, lineHeight = 20.sp)
    val Title = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(700), fontSize = 16.sp, lineHeight = 21.sp)
    val Body = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = 14.5.sp, lineHeight = 22.sp)
    val Label = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(600), fontSize = 13.sp, lineHeight = 17.sp)
    val Caption = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(600), fontSize = 11.5.sp, lineHeight = 15.sp)
    val Overline = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 0.14.em)
}

@Composable
fun NeifurTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Nf.Violet, onPrimary = Color(0xFF14102A), secondary = Nf.Pink, tertiary = Nf.Mint,
        background = Nf.Ink, onBackground = Nf.Text, surface = Nf.Surface, onSurface = Nf.Text,
        surfaceVariant = Nf.SurfaceHi, onSurfaceVariant = Nf.TextDim, outline = Nf.Line, error = Nf.Red,
        surfaceContainer = Nf.Surface, surfaceContainerHigh = Nf.SurfaceHi, surfaceContainerLow = Nf.Surface,
        surfaceContainerHighest = Nf.SurfaceHi,
    )
    val base = Typography()
    val typography = Typography(
        bodyLarge = Type.Body, bodyMedium = Type.Body, bodySmall = Type.Caption,
        titleLarge = Type.Display, titleMedium = Type.Title, titleSmall = Type.Label,
        labelLarge = Type.Label, labelMedium = Type.Caption, labelSmall = Type.Caption,
        headlineSmall = Type.Display, headlineMedium = Type.Hero,
        displaySmall = base.displaySmall.copy(fontFamily = Unbounded),
    )
    MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(LocalAccent provides Nf.Violet, content = content)
    }
}
