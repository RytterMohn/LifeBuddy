package dev.ondevice.gemma.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import dev.ondevice.gemma.app.ui.AgentTheme
import dev.ondevice.gemma.app.ui.AgentHome
import dev.ondevice.gemma.app.ui.ChatViewModel

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_TASK_ID = "dev.ondevice.gemma.app.task_id" }
    private val requestedTask = mutableStateOf("")
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedTask.value = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            AgentTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentHome(viewModel, requestedTask.value) { requestedTask.value = "" }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTask.value = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
    }
}
