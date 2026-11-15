package com.africansbiz.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =========================================================
// Couleurs AfricansBiz (mêmes valeurs que le site)
// Indigo des pagnes teints · laiton des bronzes d'Abomey · ivoire du coton
// =========================================================
private val Clair = lightColorScheme(
    primary = Color(0xFF23306E), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1F6), onPrimaryContainer = Color(0xFF101848),
    secondary = Color(0xFF474C63), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6DFD0), onSecondaryContainer = Color(0xFF161A2E),
    tertiary = Color(0xFF8A5F12), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3E3BE), onTertiaryContainer = Color(0xFF3B2900),
    error = Color(0xFFB3402A), onError = Color.White, errorContainer = Color(0xFFF6DCD5), onErrorContainer = Color(0xFF4B2017),
    background = Color(0xFFF7F3EA), onBackground = Color(0xFF161A2E),
    surface = Color(0xFFF7F3EA), onSurface = Color(0xFF161A2E),
    surfaceVariant = Color(0xFFEAE2D3), onSurfaceVariant = Color(0xFF474C63),
    outline = Color(0xFF8C8A92), outlineVariant = Color(0xFFDCD4C5),
    surfaceContainerLowest = Color(0xFFFFFDF8), surfaceContainerLow = Color(0xFFFBF8F1),
    surfaceContainer = Color(0xFFF1EBDF), surfaceContainerHigh = Color(0xFFEDE6D9), surfaceContainerHighest = Color(0xFFE7DFD0),
    inverseSurface = Color(0xFF161A2E), inverseOnSurface = Color(0xFFF7F3EA), inversePrimary = Color(0xFFB9C3FF),
)
private val Sombre = darkColorScheme(
    primary = Color(0xFFB9C3FF), onPrimary = Color(0xFF14205A),
    primaryContainer = Color(0xFF2A3470), onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFB4B3C2), onSecondary = Color(0xFF1C1F33),
    secondaryContainer = Color(0xFF262B4D), onSecondaryContainer = Color(0xFFECE8DF),
    tertiary = Color(0xFFE2B65A), onTertiary = Color(0xFF3B2900),
    tertiaryContainer = Color(0xFF4A3712), onTertiaryContainer = Color(0xFFFFE3A8),
    error = Color(0xFFFFB4A3), onError = Color(0xFF5C1A0C), errorContainer = Color(0xFF4B2017), onErrorContainer = Color(0xFFFFDAD2),
    background = Color(0xFF0E1124), onBackground = Color(0xFFECE8DF),
    surface = Color(0xFF0E1124), onSurface = Color(0xFFECE8DF),
    surfaceVariant = Color(0xFF1F2445), onSurfaceVariant = Color(0xFFB4B3C2),
    outline = Color(0xFF7D7F96), outlineVariant = Color(0xFF2C3150),
    surfaceContainerLowest = Color(0xFF0A0D1D), surfaceContainerLow = Color(0xFF131733),
    surfaceContainer = Color(0xFF171B38), surfaceContainerHigh = Color(0xFF1C2140), surfaceContainerHighest = Color(0xFF232849),
    inverseSurface = Color(0xFFECE8DF), inverseOnSurface = Color(0xFF161A2E), inversePrimary = Color(0xFF23306E),
)

// Couleurs propres à la marque (en plus du schéma Material)
@Immutable
data class Marque(val laiton: Color, val palme: Color, val palmePale: Color, val perleVide: Color)
val LocalMarque = staticCompositionLocalOf { Marque(Color(0xFFB8862B), Color(0xFF2E6B4E), Color(0xFFD6EBDD), Color(0xFFE2DACB)) }

private val Formes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(28.dp),
)

// Titres plus serrés et plus gras : même caractère que le site
private val base = Typography()
private val Typo = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, lineHeight = 40.sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
)
val Mono = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontWeight = FontWeight.Medium)

@Composable
fun AfricansBizTheme(content: @Composable () -> Unit) {
    val sombre = isSystemInDarkTheme()
    val marque = if (sombre) Marque(Color(0xFFE2B65A), Color(0xFF7FD1A6), Color(0xFF183B2B), Color(0xFF2C3150))
    else Marque(Color(0xFFB8862B), Color(0xFF2E6B4E), Color(0xFFD6EBDD), Color(0xFFE2DACB))
    CompositionLocalProvider(LocalMarque provides marque) {
        MaterialTheme(colorScheme = if (sombre) Sombre else Clair, typography = Typo, shapes = Formes, content = content)
    }
}
