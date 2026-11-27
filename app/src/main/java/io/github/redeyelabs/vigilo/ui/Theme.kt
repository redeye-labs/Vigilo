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

/** Tema scuro monocromatico: bianco, nero e grigi. Verde solo per la ricarica, rosso per gli allarmi. */
val VigiloOk = Color(0xFF30D158)
val VigiloDanger = Color(0xFFFF453A)
val VigiloIconGray = Color(0xFFC7C7CC)
private val NearBlack = Color(0xFF171717)
private val Surface = Color(0xFF1C1C1C)
private val SurfaceHigh = Color(0xFF2C2C2E)
private val Ivory = Color(0xFFFFFFFF)
private val Muted = Color(0xFF8E8E93)

private val Dark = darkColorScheme(
    primary = Ivory,
    onPrimary = Color.Black,
    primaryContainer = SurfaceHigh,
    onPrimaryContainer = Ivory,
    secondary = Color(0xFFD1D1D6),
    onSecondary = Color.Black,
    tertiary = VigiloOk,
    onTertiary = Color.Black,
    background = NearBlack,
    onBackground = Ivory,
    surface = Surface,
    onSurface = Ivory,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Muted,
    outline = Color(0xFF3A3A3C),
    outlineVariant = Color(0xFF2C2C2E),
    error = VigiloDanger,
    onError = Color.White,
    errorContainer = Color(0xFF3F1D1D),
    onErrorContainer = Color(0xFFFECACA),
    inverseSurface = Ivory,
    inverseOnSurface = Color.Black,
    scrim = Color(0xCC000000),
)

private val Type = Typography(
    displayLarge = TextStyle(fontSize = 72.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp, lineHeight = 76.sp),
    displayMedium = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
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
