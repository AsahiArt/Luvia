package tech.asahiart.luvia.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.R

/** Fallback seed when the wallpaper cannot supply a dynamic scheme. ADR 0004. */
val LuviaSeed = Color(0xFF006A62)

private val PrimaryLight = Color(0xFF006A62)
private val OnPrimaryLight = Color(0xFFFFFFFF)
private val PrimaryContainerLight = Color(0xFF9DF2E6)
private val OnPrimaryContainerLight = Color(0xFF00201D)
private val SecondaryLight = Color(0xFF4A6360)
private val OnSecondaryLight = Color(0xFFFFFFFF)
private val SecondaryContainerLight = Color(0xFFCCE8E3)
private val OnSecondaryContainerLight = Color(0xFF051F1D)
private val TertiaryLight = Color(0xFF466179)
private val OnTertiaryLight = Color(0xFFFFFFFF)
private val TertiaryContainerLight = Color(0xFFCCE5FF)
private val OnTertiaryContainerLight = Color(0xFF001D32)
private val SurfaceLight = Color(0xFFF4FBF8)
private val OnSurfaceLight = Color(0xFF161D1C)
private val OnSurfaceVariantLight = Color(0xFF3F4947)
private val OutlineLight = Color(0xFF6F7977)
private val OutlineVariantLight = Color(0xFFBEC9C6)

private val PrimaryDark = Color(0xFF80D5CA)
private val OnPrimaryDark = Color(0xFF003733)
private val PrimaryContainerDark = Color(0xFF005049)
private val OnPrimaryContainerDark = Color(0xFF9DF2E6)
private val SecondaryDark = Color(0xFFB0CCC7)
private val OnSecondaryDark = Color(0xFF1C3532)
private val SecondaryContainerDark = Color(0xFF334B48)
private val OnSecondaryContainerDark = Color(0xFFCCE8E3)
private val TertiaryDark = Color(0xFFB0CAE6)
private val OnTertiaryDark = Color(0xFF163349)
private val TertiaryContainerDark = Color(0xFF2E4960)
private val OnTertiaryContainerDark = Color(0xFFCCE5FF)
private val SurfaceDark = Color(0xFF0E1514)
private val OnSurfaceDark = Color(0xFFDDE4E1)
private val OnSurfaceVariantDark = Color(0xFFBEC9C6)

private val ErrorLight = Color(0xFFBA1A1A)
private val OnErrorLight = Color(0xFFFFFFFF)
private val ErrorContainerLight = Color(0xFFFFDAD6)
private val OnErrorContainerLight = Color(0xFF410002)
private val ErrorDark = Color(0xFFFFB4AB)
private val OnErrorDark = Color(0xFF690005)
private val ErrorContainerDark = Color(0xFF93000A)
private val OnErrorContainerDark = Color(0xFFFFDAD6)

private val TerminalBg = Color(0xFF101615)
private val TerminalFg = Color(0xFFE4EEEB)

@Immutable
data class LuviaExtendedColors(
    val live: Color,
    val connecting: Color,
    val stale: Color,
    val offline: Color,
    val terminalBg: Color,
    val terminalFg: Color,
    val agentIdle: Color,
    val agentWorking: Color,
    val agentBlocked: Color,
    val agentUnknown: Color,
    val diffAdd: Color,
    val diffDel: Color,
)

private val LocalLuviaExtendedColors = staticCompositionLocalOf {
    LuviaExtendedColors(
        live = Color(0xFF2E9E5B),
        connecting = TertiaryLight,
        stale = Color(0xFF8A5100),
        offline = OutlineLight,
        terminalBg = TerminalBg,
        terminalFg = TerminalFg,
        agentIdle = OnSurfaceVariantLight,
        agentWorking = TertiaryLight,
        agentBlocked = TertiaryLight,
        agentUnknown = OutlineLight,
        diffAdd = Color(0xFF0B6E4F),
        diffDel = Color(0xFFBA1A1A),
    )
}

private val LuviaLightExtended = LuviaExtendedColors(
    live = Color(0xFF2E9E5B),
    connecting = TertiaryLight,
    stale = Color(0xFF8A5100),
    offline = OutlineLight,
    terminalBg = TerminalBg,
    terminalFg = TerminalFg,
    agentIdle = OnSurfaceVariantLight,
    agentWorking = TertiaryLight,
    agentBlocked = TertiaryLight,
    agentUnknown = OutlineLight,
    diffAdd = Color(0xFF0B6E4F),
    diffDel = Color(0xFFBA1A1A),
)

private val LuviaDarkExtended = LuviaExtendedColors(
    live = Color(0xFF6FDB9A),
    connecting = TertiaryDark,
    stale = Color(0xFFFFB870),
    offline = Color(0xFF8A9391),
    terminalBg = TerminalBg,
    terminalFg = TerminalFg,
    agentIdle = OnSurfaceVariantDark,
    agentWorking = TertiaryDark,
    agentBlocked = TertiaryDark,
    agentUnknown = Color(0xFF8A9391),
    diffAdd = Color(0xFF81C784),
    diffDel = Color(0xFFFFB4AB),
)

private val LuviaLightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    inversePrimary = PrimaryDark,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = Color(0xFFE3EAE7),
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceTint = PrimaryLight,
    inverseSurface = Color(0xFF2B3231),
    inverseOnSurface = Color(0xFFECF2EF),
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF4FBF8),
    surfaceDim = Color(0xFFD5DBD8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEEF5F2),
    surfaceContainer = Color(0xFFE9EFEC),
    surfaceContainerHigh = Color(0xFFE3EAE7),
    surfaceContainerHighest = Color(0xFFDDE4E1),
)

private val LuviaDarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    inversePrimary = PrimaryLight,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = Color(0xFF3F4947),
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceTint = PrimaryDark,
    inverseSurface = Color(0xFFDDE4E1),
    inverseOnSurface = Color(0xFF2B3231),
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4947),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF343B39),
    surfaceDim = SurfaceDark,
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF161D1C),
    surfaceContainer = Color(0xFF1A2120),
    surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerHighest = Color(0xFF303635),
)

private val LuviaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

object LuviaTheme {
    val extended: LuviaExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalLuviaExtendedColors.current

    val mono: FontFamily = FontFamily(
        Font(R.font.jetbrains_mono_nf, FontWeight.Normal),
    )
}

@Composable
fun LuviaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> LuviaDarkColorScheme
        else -> LuviaLightColorScheme
    }
    val extended = if (darkTheme) LuviaDarkExtended else LuviaLightExtended
    CompositionLocalProvider(LocalLuviaExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            shapes = LuviaShapes,
            content = content,
        )
    }
}
