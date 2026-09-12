package tech.asahiart.luvia.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Bottom inset for gesture/nav/IME, read from Compose and the root view.
 * Compose [navigationBars] is often 0 in landscape gesture-nav after a sibling
 * Scaffold consumes insets, so this also reads the view and floors at 48.dp.
 */
@Composable
fun systemBottomInset(): Dp {
    val density = LocalDensity.current
    val view = LocalView.current
    val composePx = maxOf(
        WindowInsets.navigationBars.getBottom(density),
        WindowInsets.systemBars.getBottom(density),
        WindowInsets.safeDrawing.getBottom(density),
        WindowInsets.mandatorySystemGestures.getBottom(density),
    )
    val viewPx = ViewCompat.getRootWindowInsets(view)?.let { insets ->
        maxOf(
            insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
            insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom,
        )
    } ?: 0
    return with(density) { maxOf(composePx, viewPx).toDp() }.coerceAtLeast(48.dp)
}

fun Modifier.systemBottomPadding(): Modifier = composed {
    padding(bottom = systemBottomInset())
}
