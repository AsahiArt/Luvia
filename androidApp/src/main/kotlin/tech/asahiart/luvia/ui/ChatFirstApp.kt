package tech.asahiart.luvia.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Placeholder shell after the v2 Now/Projects UI was removed. */
@Composable
fun ChatFirstApp(hostCount: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (hostCount == 0) "Luvia" else "Luvia · $hostCount paired",
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
