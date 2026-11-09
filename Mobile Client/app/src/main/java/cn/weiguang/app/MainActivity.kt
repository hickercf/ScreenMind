package cn.weiguang.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.weiguang.app.ui.WeiguangRoot
import cn.weiguang.app.ui.WeiguangTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    private val sharedImage = MutableStateFlow<Uri?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        receiveShare(intent)
        setContent {
            val mode = model.theme.collectAsStateWithLifecycle().value
            val uri = sharedImage.collectAsStateWithLifecycle().value
            WeiguangTheme(mode) { WeiguangRoot(model, uri) { sharedImage.value = null } }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveShare(intent) }
    private fun receiveShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            sharedImage.value = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        }
    }
}
