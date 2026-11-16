package tech.asahiart.luvia

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.util.Consumer
import tech.asahiart.luvia.ui.theme.LuviaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applyLuviaEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            var launchIntent by remember { mutableStateOf(intent) }
            var launchEventId by rememberSaveable { mutableLongStateOf(0L) }
            val activity = this@MainActivity
            DisposableEffect(activity) {
                val listener = Consumer<Intent> { incoming ->
                    launchEventId += 1
                    launchIntent = incoming
                }
                activity.addOnNewIntentListener(listener)
                onDispose { activity.removeOnNewIntentListener(listener) }
            }
            LuviaTheme {
                Surface {
                    LuviaApp(launchIntent = launchIntent, launchEventId = launchEventId)
                }
            }
        }
    }
}

/** Edge-to-edge, then drop the platform nav-bar scrim [enableEdgeToEdge] turns on. */
internal fun ComponentActivity.applyLuviaEdgeToEdge() {
    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }
}

