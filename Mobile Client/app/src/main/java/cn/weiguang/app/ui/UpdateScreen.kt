package cn.weiguang.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.weiguang.app.AppViewModel
import cn.weiguang.app.BuildConfig

@Composable
fun UpdateScreen(model: AppViewModel, endpoint: String, onEndpoint: () -> Unit) {
    val state by model.updater.state.collectAsStateWithLifecycle()
    val busy = state.phase == "checking" || state.phase == "downloading"
    Text("应用更新", style = MaterialTheme.typography.headlineLarge)
    Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
    Text("服务地址：$endpoint", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = onEndpoint, enabled = !busy) { Text("设置服务地址") }
    Text(state.message)
    state.release?.let { release ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("新版 ${release.name}", style = MaterialTheme.typography.titleLarge)
                Text("安装包 %.1f MB".format(release.size / 1024.0 / 1024.0))
                if (release.notes.isNotBlank()) Text(release.notes)
                when (state.phase) {
                    "downloading" -> {
                        LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("已下载 ${state.progress}% · 请保持应用开启")
                        TextButton(onClick = model.updater::cancel) { Text("取消下载") }
                    }
                    "ready" -> Button(onClick = model.updater::install) { Text("安装更新") }
                    "available" -> Button(onClick = model.updater::download) { Text("下载新版") }
                }
            }
        }
    }
    OutlinedButton(onClick = { model.updater.check(endpoint) }, enabled = !busy) { Text("检查更新") }
    Text("应用启动或回到前台时会自动检查更新，同一服务地址每 4 小时最多检查一次。下载由你发起；安装需要系统确认，首次可能需要允许本应用安装更新。", style = MaterialTheme.typography.bodySmall)
}
