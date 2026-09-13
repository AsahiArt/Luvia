package tech.asahiart.luvia

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.util.Consumer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            var launchIntent by remember { mutableStateOf(intent) }
            val activity = this@MainActivity
            DisposableEffect(activity) {
                val listener = Consumer<Intent> { incoming -> launchIntent = incoming }
                activity.addOnNewIntentListener(listener)
                onDispose { activity.removeOnNewIntentListener(listener) }
            }
            LuviaTheme {
                Surface {
                    LuviaApp(launchIntent = launchIntent)
                }
            }
        }
    }
}

@Composable
private fun LuviaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
