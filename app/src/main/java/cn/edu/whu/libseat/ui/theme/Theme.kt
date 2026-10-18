package cn.edu.whu.libseat.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** 珞珈绿 + 樱粉，借武大校园的视觉记忆。 */
private val WhuGreen = Color(0xFF0E5A3A)
private val WhuGreenDark = Color(0xFF0A3D28)
private val Sakura = Color(0xFFC2607A)

private val LightColors = lightColorScheme(
    primary = WhuGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EEDF),
    onPrimaryContainer = Color(0xFF06301E),
    secondary = Color(0xFF53685B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2EEE5),
    onSecondaryContainer = Color(0xFF16251C),
    tertiary = Sakura,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFADCE5),
    onTertiaryContainer = Color(0xFF47101F),
    background = Color(0xFFF5F7F2),
    onBackground = Color(0xFF171D19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFE7EEE8),
    onSurfaceVariant = Color(0xFF58655C),
    outline = Color(0xFFC6D1C8),
    outlineVariant = Color(0xFFDCE5DE),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD9B4),
    onPrimary = Color(0xFF003921),
    primaryContainer = Color(0xFF145036),
    onPrimaryContainer = Color(0xFFD5EEDF),
    secondary = Color(0xFFB7CCBE),
    onSecondary = Color(0xFF22352A),
    secondaryContainer = Color(0xFF33463A),
    onSecondaryContainer = Color(0xFFD3E8DA),
    tertiary = Color(0xFFF0B7C8),
    onTertiary = Color(0xFF4B1526),
    tertiaryContainer = Color(0xFF66283A),
    onTertiaryContainer = Color(0xFFFFD9E3),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFE3EAE4),
    surface = Color(0xFF161E19),
    onSurface = Color(0xFFE3EAE4),
    surfaceVariant = Color(0xFF253129),
    onSurfaceVariant = Color(0xFFB5C2B8),
    outline = Color(0xFF6F7C72),
    outlineVariant = Color(0xFF37433A),
    error = Color(0xFFFFB4AB),
)

// 座位状态色（涨红跌绿不适用，这里遵循“绿=可用、灰=占用”的座位惯例）
val SeatFreeLight = Color(0xFFCDEBD8)
val SeatFreeText = Color(0xFF0B5D3B)
val SeatBusyLight = Color(0xFFE4E7E2)
val SeatBusyText = Color(0xFF8A948C)
val SeatAwayLight = Color(0xFFFBE3B8)
val SeatAwayText = Color(0xFF8A5A00)
val SeatReservedLight = Color(0xFFD6E3F5)
val SeatReservedText = Color(0xFF3A5680)

val SeatFreeDark = Color(0xFF1E4A33)
val SeatFreeTextDark = Color(0xFF9FE3BC)
val SeatBusyDark = Color(0xFF2A332C)
val SeatBusyTextDark = Color(0xFF7C8A80)
val SeatAwayDark = Color(0xFF4A3A18)
val SeatAwayTextDark = Color(0xFFF0C579)
val SeatReservedDark = Color(0xFF243A55)
val SeatReservedTextDark = Color(0xFFA9C7EE)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp),
)

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Composable
fun LibrarySeatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/** 统一的座位状态色，随主题切换。 */
data class SeatPalette(
    val freeBg: Color,
    val freeText: Color,
    val busyBg: Color,
    val busyText: Color,
    val awayBg: Color,
    val awayText: Color,
    val reservedBg: Color,
    val reservedText: Color,
)

@Composable
fun seatPalette(): SeatPalette {
    val dark = isSystemInDarkTheme()
    return if (dark) {
        SeatPalette(
            SeatFreeDark, SeatFreeTextDark,
            SeatBusyDark, SeatBusyTextDark,
            SeatAwayDark, SeatAwayTextDark,
            SeatReservedDark, SeatReservedTextDark,
        )
    } else {
        SeatPalette(
            SeatFreeLight, SeatFreeText,
            SeatBusyLight, SeatBusyText,
            SeatAwayLight, SeatAwayText,
            SeatReservedLight, SeatReservedText,
        )
    }
}

val WhuGreenBrand = WhuGreen
val WhuGreenDeep = WhuGreenDark
