package cn.weiguang.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cn.weiguang.app.data.LocalEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

private val searchWhitespace = Regex("[\\s\\p{Z}]+")

@Composable
internal fun ClueLibrary(
    events: List<LocalEvent>,
    query: String,
    onQueryChange: (String) -> Unit,
    listState: LazyListState,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    captureActions: @Composable ColumnScope.() -> Unit,
    eventCard: @Composable (LocalEvent) -> Unit,
) {
    val terms = remember(query) { query.trim().split(searchWhitespace).filter(String::isNotEmpty) }
    var matches by remember(events, terms) { mutableStateOf<List<LocalEvent>?>(null) }
    LaunchedEffect(events, terms) {
        if (terms.isNotEmpty()) matches = withContext(Dispatchers.Default) {
            val job = currentCoroutineContext()
            val zone = ZoneId.systemDefault()
            events.filter { event ->
                job.ensureActive()
                val searchable = listOfNotNull(ReadableText.clue(event.text), eventLabel(event), formatTime(event.capturedAt),
                    event.occurredAt?.let { ReadableText.dateTime(it, zone) }).joinToString(" ")
                terms.all { searchable.contains(it, ignoreCase = true) }
            }
        }
    }
    val searching = terms.isNotEmpty()
    val visible = if (searching) matches else events
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val finishEditing: () -> Unit = { keyboard?.hide(); focus.clearFocus() }
    BackHandler(editing, onBack = finishEditing)
    DisposableEffect(Unit) { onDispose { onEditingChange(false) } }
    val scope = rememberCoroutineScope()
    val changeQuery: (String) -> Unit = { text ->
        onQueryChange(text)
        if (listState.firstVisibleItemIndex > 0) scope.launch { listState.scrollToItem(0) }
    }

    Column(Modifier.widthIn(max = 620.dp).fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!editing) Row(verticalAlignment = Alignment.CenterVertically) {
                WorkspaceHeader("你的线索", modifier = Modifier.weight(1f))
                if (!searching && events.size > 1) Note("共 ${events.size} 条")
            }
            if (events.isNotEmpty() || query.isNotEmpty()) {
                OutlinedTextField(
                    value = query, onValueChange = changeQuery, singleLine = true,
                    label = { Text("搜索线索") },
                    leadingIcon = {
                        if (editing) IconButton(onClick = finishEditing) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "结束输入")
                        } else Icon(Icons.Outlined.Search, null)
                    },
                    trailingIcon = if (query.isEmpty()) null else ({
                        IconButton(onClick = { changeQuery("") }) {
                            Icon(Icons.Outlined.Close, "清除搜索")
                        }
                    }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { finishEditing() }),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { onEditingChange(it.hasFocus) }.testTag("clue-search"),
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("clue-library"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!searching) item(key = "capture") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = captureActions)
            }
            if (visible == null) {
                item(key = "searching") { Note("正在查找线索…") }
            } else if (visible.isEmpty()) {
                item(key = "empty") {
                    WorkspaceCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            WorkspaceGlyph(if (searching) Icons.Outlined.Search else Icons.Outlined.Description)
                            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                                Text(if (searching) "没有找到匹配的线索" else "还没有线索", style = MaterialTheme.typography.titleMedium)
                                Note(if (searching) "试试地点、时间，或线索里的几个字。" else "记下一件事，或导入一张截图。")
                            }
                        }
                    }
                }
            } else {
                if (searching) item(key = "result-count") {
                    Text("找到 ${visible.size} 条线索", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                items(visible, key = { "event-${it.eventId}" }, contentType = { "clue" }) { event ->
                    WorkspaceCard { eventCard(event) }
                }
            }
        }
    }
}
