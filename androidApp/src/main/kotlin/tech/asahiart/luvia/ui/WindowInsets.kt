package tech.asahiart.luvia.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Bottom nav/gesture inset. Reads the root view so a sibling Scaffold cannot
 * zero out Compose [WindowInsets.navigationBars]. Does not use systemGestures
 * (those can be huge and crush the composer).
 */
@Composable
fun systemBottomInset(): Dp {
    val density = LocalDensity.current
    val view = LocalView.current
    val composePx = WindowInsets.navigationBars.getBottom(density)
    val viewPx = ViewCompat.getRootWindowInsets(view)
        ?.getInsets(WindowInsetsCompat.Type.navigationBars())
        ?.bottom
        ?: 0
    val px = maxOf(composePx, viewPx, with(density) { 24.dp.roundToPx() })
    return with(density) { px.toDp() }
}

@Composable
fun Modifier.systemBottomPadding(): Modifier = padding(bottom = systemBottomInset())
