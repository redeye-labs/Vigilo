package io.github.redeyelabs.vigilo.ui

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** Tema scuro monocromatico: gli stati sono identificati anche da testo e icone. */
val VigiloOk = Color(0xFFE0E0E0)
val VigiloDanger = Color(0xFFF5F5F5)
val VigiloIconGray = Color(0xFFCCCCCC)
private val NearBlack = Color(0xFF101010)
private val Surface = Color(0xFF1C1C1C)
private val SurfaceHigh = Color(0xFF292929)
private val Foreground = Color(0xFFF5F5F5)
private val Muted = Color(0xFFBDBDBD)

private val Dark = darkColorScheme(
    primary = Color(0xFFF5F5F5),
    onPrimary = Color(0xFF151515),
    primaryContainer = SurfaceHigh,
    onPrimaryContainer = Foreground,
    secondary = Color(0xFFD1D1D1),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF383838),
    onSecondaryContainer = Foreground,
    tertiary = VigiloOk,
    onTertiary = Color.Black,
    tertiaryContainer = SurfaceHigh,
    onTertiaryContainer = Foreground,
    background = NearBlack,
    onBackground = Foreground,
    surface = Surface,
    onSurface = Foreground,
    surfaceVariant = SurfaceHigh,
    surfaceTint = Foreground,
    surfaceDim = NearBlack,
    surfaceBright = Color(0xFF383838),
    surfaceContainerLowest = NearBlack,
    surfaceContainerLow = Color(0xFF161616),
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = Color(0xFF383838),
    inversePrimary = Color(0xFF383838),
    onSurfaceVariant = Muted,
    outline = Color(0xFF666666),
    outlineVariant = Color(0xFF383838),
    error = VigiloDanger,
    onError = Color.White,
    errorContainer = Color(0xFF333333),
    onErrorContainer = Color(0xFFF5F5F5),
    inverseSurface = Foreground,
    inverseOnSurface = Color.Black,
    scrim = Color(0xCC000000),
)

private val Type = Typography(
    displayLarge = TextStyle(fontSize = 72.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp, lineHeight = 76.sp),
    displayMedium = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.SemiBold, lineHeight = 42.sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

private val Shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun VigiloTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(colorScheme = Dark, typography = Type, shapes = Shapes, content = content)
}
