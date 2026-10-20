package dev.zacx.muster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zacx.muster.core.model.ActionId

/**
 * Phase 0 placeholder. Phase 1 replaces this with the server-URL screen and
 * the WebView shell (feature/shell).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                        Text("muster", style = MaterialTheme.typography.headlineMedium)
                        Text("Phase 0 scaffold. ${ActionId.entries.size} action ids known.")
                    }
                }
            }
        }
    }
}
