package tech.asahiart.luvia.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private val CanvasLight = Color(0xFFF4F0EA)
private val SurfaceLight = Color(0xFFFFFBF6)
private val SurfaceContainerLight = Color(0xFFEFE8DF)
private val InkLight = Color(0xFF1C1916)
private val InkMutedLight = Color(0xFF6A635C)
private val AccentLight = Color(0xFFC45C26)
private val OnAccentLight = Color(0xFFFFFBF6)
private val LiveLight = Color(0xFF2F6F4E)
private val ConnectingLight = Color(0xFF3D6B99)
private val StaleLight = Color(0xFFB56A1B)
private val Offline = Color(0xFF8A837C)

private val CanvasDark = Color(0xFF161412)
private val SurfaceDark = Color(0xFF1E1B18)
private val SurfaceContainerDark = Color(0xFF26221E)
private val InkDark = Color(0xFFF3EDE6)
private val InkMutedDark = Color(0xFFA39B93)
private val AccentDark = Color(0xFFE07A42)
private val OnAccentDark = Color(0xFF1C1916)
private val LiveDark = Color(0xFF5BA87A)
private val ConnectingDark = Color(0xFF7BA3C9)
private val StaleDark = Color(0xFFE09A4A)

private val TerminalBg = Color(0xFF1A1815)
private val TerminalFg = Color(0xFFE8E2D8)

private val ErrorLight = Color(0xFFB3261E)
private val OnErrorLight = Color(0xFFFFFFFF)
private val ErrorContainerLight = Color(0xFFF9DEDC)
private val OnErrorContainerLight = Color(0xFF410E0B)

private val ErrorDark = Color(0xFFE46962)
private val OnErrorDark = Color(0xFF1C1916)
private val ErrorContainerDark = Color(0xFF8C1D18)
private val OnErrorContainerDark = Color(0xFFF9DEDC)

@Immutable
data class LuviaExtendedColors(
    val live: Color,
    val connecting: Color,
    val stale: Color,
    val offline: Color,
    val terminalBg: Color,
    val terminalFg: Color,
)

private val LocalLuviaExtendedColors = staticCompositionLocalOf {
    LuviaExtendedColors(
        live = LiveLight,
        connecting = ConnectingLight,
        stale = StaleLight,
        offline = Offline,
        terminalBg = TerminalBg,
        terminalFg = TerminalFg,
    )
}

private val LuviaLightExtended = LuviaExtendedColors(
    live = LiveLight,
    connecting = ConnectingLight,
    stale = StaleLight,
    offline = Offline,
    terminalBg = TerminalBg,
    terminalFg = TerminalFg,
)

private val LuviaDarkExtended = LuviaExtendedColors(
    live = LiveDark,
    connecting = ConnectingDark,
    stale = StaleDark,
    offline = Offline,
    terminalBg = TerminalBg,
    terminalFg = TerminalFg,
)

private val LuviaLightColorScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = OnAccentLight,
    primaryContainer = Color(0xFFF2D8C8),
    onPrimaryContainer = InkLight,
    inversePrimary = AccentDark,
    secondary = InkMutedLight,
    onSecondary = SurfaceLight,
    secondaryContainer = SurfaceContainerLight,
    onSecondaryContainer = InkLight,
    tertiary = LiveLight,
    onTertiary = OnAccentLight,
    tertiaryContainer = Color(0xFFD5E8DC),
    onTertiaryContainer = InkLight,
    background = CanvasLight,
    onBackground = InkLight,
    surface = SurfaceLight,
    onSurface = InkLight,
    surfaceVariant = SurfaceContainerLight,
    onSurfaceVariant = InkMutedLight,
    surfaceTint = AccentLight,
    inverseSurface = CanvasDark,
    inverseOnSurface = InkDark,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    outline = Offline,
    outlineVariant = Color(0xFFDDD4CA),
    scrim = Color(0xFF000000),
    surfaceBright = SurfaceLight,
    surfaceDim = Color(0xFFE6DFD6),
    surfaceContainerLowest = SurfaceLight,
    surfaceContainerLow = Color(0xFFF8F3EC),
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = Color(0xFFE8E0D6),
    surfaceContainerHighest = Color(0xFFE0D8CE),
)

private val LuviaDarkColorScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = OnAccentDark,
    primaryContainer = Color(0xFF543624),
    onPrimaryContainer = InkDark,
    inversePrimary = AccentLight,
    secondary = InkMutedDark,
    onSecondary = OnAccentDark,
    secondaryContainer = SurfaceContainerDark,
    onSecondaryContainer = InkDark,
    tertiary = LiveDark,
    onTertiary = OnAccentDark,
    tertiaryContainer = Color(0xFF1E3A2C),
    onTertiaryContainer = InkDark,
    background = CanvasDark,
    onBackground = InkDark,
    surface = SurfaceDark,
    onSurface = InkDark,
    surfaceVariant = SurfaceContainerDark,
    onSurfaceVariant = InkMutedDark,
    surfaceTint = AccentDark,
    inverseSurface = CanvasLight,
    inverseOnSurface = InkLight,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    outline = Offline,
    outlineVariant = Color(0xFF3A3530),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2C2824),
    surfaceDim = CanvasDark,
    surfaceContainerLowest = Color(0xFF12100E),
    surfaceContainerLow = Color(0xFF1A1815),
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = Color(0xFF2C2824),
    surfaceContainerHighest = Color(0xFF35302B),
)

private val LuviaTypography = run {
    val base = Typography()
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = FontFamily.Serif),
        displayMedium = base.displayMedium.copy(fontFamily = FontFamily.Serif),
        displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Serif),
        headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Serif),
        headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif),
    )
}

private val LuviaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

object LuviaTheme {
    val extended: LuviaExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalLuviaExtendedColors.current
}

@Composable
fun LuviaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) LuviaDarkColorScheme else LuviaLightColorScheme
    val extended = if (darkTheme) LuviaDarkExtended else LuviaLightExtended
    CompositionLocalProvider(LocalLuviaExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LuviaTypography,
            shapes = LuviaShapes,
            content = content,
        )
    }
}
