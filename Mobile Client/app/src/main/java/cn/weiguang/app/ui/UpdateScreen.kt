package cn.weiguang.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.weiguang.app.AppViewModel
import cn.weiguang.app.BuildConfig

@Composable
fun UpdateScreen(model: AppViewModel, endpoint: String, onEndpoint: () -> Unit) {
    val state by model.updater.state.collectAsStateWithLifecycle()
    val busy = state.phase in setOf("checking", "downloading", "verifying")
    WorkspaceHeader("应用更新")
    WorkspaceCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            WorkspaceGlyph(Icons.Outlined.SystemUpdate, accent = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
                Text(
                    ReadableText.error(state.message, "暂时无法读取更新，请稍后重试。"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (!BuildConfig.LOCK_CLOUD_ENDPOINT) {
                WorkspaceButton(
                    onClick = onEndpoint,
                    enabled = !busy,
                    secondary = true,
                    modifier = Modifier.weight(1f),
                ) { Text("设置服务地址") }
            }
            WorkspaceButton(
                onClick = { model.updater.check(endpoint) },
                enabled = !busy,
                secondary = state.release != null,
                modifier = Modifier.weight(1f),
            ) { Text("检查更新") }
        }
    }
    state.release?.let { release ->
        WorkspaceCard(accent = true) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("新版 ${release.name}", style = MaterialTheme.typography.titleLarge)
                WorkspaceBadge("安装包 %.1f MB".format(release.size / 1_000_000.0), accent = true)
            }
            if (release.notes.isNotBlank()) {
                Text(
                    ReadableText.text(release.notes, "暂无更新说明。"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.phase == "downloading" || state.phase == "paused") {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                )
                Text(
                    "已下载 ${state.progress}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (state.phase) {
                "downloading" -> WorkspaceButton(onClick = model.updater::pause, secondary = true, modifier = Modifier.fillMaxWidth()) { Text("暂停下载") }
                "paused" -> WorkspaceButton(onClick = model.updater::download, modifier = Modifier.fillMaxWidth()) { Text("继续下载") }
                "verifying" -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(CircleShape))
                "ready" -> WorkspaceButton(onClick = model.updater::install, modifier = Modifier.fillMaxWidth()) { Text("安装更新") }
                "available" -> WorkspaceButton(onClick = model.updater::download, modifier = Modifier.fillMaxWidth()) { Text("下载新版") }
            }
        }
    }
    if (state.phase == "ready") {
        Text(
            "按系统提示安装。首次更新可能需要授予安装权限。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
