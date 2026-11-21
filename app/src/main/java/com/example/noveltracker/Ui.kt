package com.example.noveltracker

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ theme
/** Colours follow your Excel rules: green = done/yes, blue = running, yellow = not started/maybe, red = no/aborted. */
object Pal {
    val Green = Color(0xFF92D050)
    val Blue = Color(0xFF8ED8E0)
    val Yellow = Color(0xFFF2D04B)
    val Red = Color(0xFFF28B82)
    val Track = Color(0xFF2A323B)
}

fun statusColor(v: String): Color = when (v) {
    "Completed", "Yes" -> Pal.Green
    "Running" -> Pal.Blue
    "Not Started", "Maybe" -> Pal.Yellow
    "Aborted", "No" -> Pal.Red
    else -> Color.Gray
}

fun whenText(ms: Long): String =
    if (ms <= 0) "never" else android.text.format.DateFormat.format("d MMM yyyy, HH:mm", ms).toString()

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.Green, onPrimary = Color(0xFF14210B),
            secondaryContainer = Color(0xFF2A3F2B), onSecondaryContainer = Color(0xFFDDF2C9),
            background = Color(0xFF0F1318), onBackground = Color(0xFFE6EAEE),
            surface = Color(0xFF171D24), onSurface = Color(0xFFE6EAEE),
            surfaceVariant = Color(0xFF222A33), onSurfaceVariant = Color(0xFF9AA5B1),
            outline = Color(0xFF3A4551),
        ),
        content = content,
    )
}

// ------------------------------------------------------------------ small pieces
@Composable
fun Tag(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.16f)) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** Green = read, blue = downloaded but unread, dark = not downloaded yet. */
@Composable
fun StackBar(p: Progress, height: Int = 8) {
    val total = maxOf(p.total, 1).toFloat()
    val r = p.read / total
    val d = (p.read + p.downloadedUnread) / total
    Canvas(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(4.dp))) {
        drawRect(Pal.Track)
        drawRect(Pal.Blue, size = Size(size.width * d, size.height))
        drawRect(Pal.Green, size = Size(size.width * r, size.height))
    }
}

/**
 * Three-segment ring: green = read, blue = downloaded but unread, dark = not downloaded. Empty ring for zero chapters.
 * The centre shows the read count; the downloaded count is shown as text on the card.
 */
@Composable
fun ProgressRing(p: Progress, centerText: String, modifier: Modifier = Modifier, diameter: Dp = 56.dp, strokeWidth: Dp = 6.dp) {
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = strokeWidth.toPx()
            val topLeft = Offset(sw / 2, sw / 2)
            val arc = Size(this.size.width - sw, this.size.height - sw)
            drawArc(Pal.Track, 0f, 360f, false, topLeft, arc, style = Stroke(sw))
            if (p.total > 0) {
                drawArc(Pal.Blue, -90f, 360f * (p.read + p.downloadedUnread) / p.total, false, topLeft, arc, style = Stroke(sw))
                drawArc(Pal.Green, -90f, 360f * p.read / p.total, false, topLeft, arc, style = Stroke(sw))
            }
        }
        Text(centerText, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
fun Stat(label: String, value: Int, color: Color, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).padding(8.dp)) {
        Text(fmt(value), style = MaterialTheme.typography.headlineSmall, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SwitchRow(label: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.defaultMinSize(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Chips where the stored value differs from the label (value to label pairs). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceKV(label: String, options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, l) -> FilterChip(selected = v == value, onClick = { onPick(v) }, label = { Text(l) }) }
        }
    }
}

@Composable
fun Choice(label: String, options: List<String>, value: String, onPick: (String) -> Unit) =
    ChoiceKV(label, options.map { it to it }, value, onPick)

/** Keeps digits and the first '.', so typing 101.1 stays 101.1 instead of turning into 1011. */
private fun cleanNumberInput(s: String): String {
    val dot = s.indexOf('.')
    return if (dot < 0) s.filter(Char::isDigit) else s.substring(0, dot).filter(Char::isDigit) + "." + s.substring(dot + 1).filter(Char::isDigit)
}

/** The whole chapter number of typed text: "101.1" -> 101 (the part after the point is dropped, never joined on). */
private fun wholeOf(s: String): Int? = s.substringBefore('.').toIntOrNull()

// ------------------------------------------------------------------ dialogs
/**
 * Whole-number editor. Blank/invalid input can't be saved. A value above [warnAbove] or below [warnBelow] (when > 0)
 * shows a warning as soon as it is typed and the button becomes "Save anyway", so it is never silently accepted or clamped.
 */
@Composable
fun NumberDialog(
    title: String, initial: Int, onDismiss: () -> Unit,
    warnAbove: Int = 0, warnText: String = "",
    warnBelow: Int = 0, warnBelowText: String = "", onSave: (Int) -> Unit,
) {
    var t by remember { mutableStateOf(initial.toString()) }
    var submitted by remember { mutableStateOf(false) }
    val v = wholeOf(t)
    val tooHigh = v != null && warnAbove > 0 && v > warnAbove
    val tooLow = v != null && warnBelow > 0 && v < warnBelow
    val over = tooHigh || tooLow
    val message = if (tooHigh) warnText else warnBelowText
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = t, onValueChange = { t = cleanNumberInput(it) }, singleLine = true,
                    isError = v == null || over,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (v == null) Text("Enter a whole number", style = MaterialTheme.typography.bodySmall, color = Pal.Red)
                else if (over) Text(message, style = MaterialTheme.typography.bodySmall, color = Pal.Yellow)
                if (v != null && '.' in t) Text("Counted as chapter ${fmt(v)} (the part after the point is dropped)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = v != null && !submitted, onClick = {
                if (v == null) return@TextButton
                submitted = true; onSave(v); onDismiss()
            }) { Text(if (over) "Save anyway" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ProgressDialog(n: Novel, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var t by remember { mutableStateOf(n.readCount.toString()) }
    var submitted by remember { mutableStateOf(false) }
    val v = wholeOf(t)
    val cur = v ?: 0
    // A read count past the latest known chapter is possible (site count may be stale) but must be confirmed.
    val over = v != null && n.currentCount > 0 && v > n.currentCount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(n.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = t, onValueChange = { t = cleanNumberInput(it) }, singleLine = true,
                    label = { Text("Chapters read") },
                    isError = v == null || over,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (v == null) Text("Enter a whole number", style = MaterialTheme.typography.bodySmall, color = Pal.Red)
                else if (over) Text(
                    "Chapter ${fmt(v)} is past the latest known chapter (${fmt(n.currentCount)}). You can still save it.",
                    style = MaterialTheme.typography.bodySmall, color = Pal.Yellow,
                )
                if (v != null && '.' in t) Text("Counted as chapter ${fmt(v)} (the part after the point is dropped)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 10, 50).forEach { d -> AssistChip(onClick = { t = (cur + d).toString() }, label = { Text("+$d") }) }
                }
                Text(
                    "Downloaded through ${fmt(n.downloadedThrough)}  ·  latest ${fmt(n.currentCount)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (n.downloadedThrough > cur) {
                    OutlinedButton(onClick = { t = n.downloadedThrough.toString() }) { Text("Set to downloaded (${fmt(n.downloadedThrough)})") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = v != null && !submitted, onClick = {
                if (v == null) return@TextButton
                submitted = true; onSave(v); onDismiss()
            }) { Text(if (over) "Save anyway" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AddDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Add novel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    link, { link = it }, label = { Text("Link (optional)") }, singleLine = true,
                    placeholder = { Text("spirit-realm.html") },
                    supportingText = { Text(if (link.isBlank()) "Empty: I'll try \"${slugFromName(name)}\" on both sites" else "Filled from the site automatically") },
                )
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onAdd(name, link); onDismiss() }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RefreshAllDialog(all: List<Novel>, onDismiss: () -> Unit, onStart: (skip: Boolean, background: Boolean) -> Unit) {
    var skip by remember { mutableStateOf(true) }
    var background by remember { mutableStateOf(false) }
    val linked = all.filter { it.slug.isNotBlank() }
    val pages = linked.sumOf { n -> n.sites.split(",").count { it.trim() in BASES }.let { if (it == 0) BASES.size else it } }
    val minutes = (pages * 4 / 60).coerceAtLeast(1)
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Refresh all links?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Loads ${linked.size} novels' pages (about $pages page loads, roughly $minutes min) and uses mobile data. Keep the app open; the screen stays on.")
                Text(
                    "Chapter counts only update automatically when safe; anything unusual goes to Review counts.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = skip, onCheckedChange = { skip = it })
                    Text("Skip novels already refreshed today", Modifier.clickable { skip = !skip }.padding(vertical = 12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = background, onCheckedChange = { background = it })
                    Text("Keep going in the background (needs a connection; a bot check pauses it)", Modifier.clickable { background = !background }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(skip, background); onDismiss() }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ app shell
@Composable
fun App(vm: NovelViewModel) {
    var screen by rememberSaveable { mutableStateOf("lib") }
    var openUid by rememberSaveable { mutableStateOf("") }
    var detailBack by rememberSaveable { mutableStateOf("lib") }
    // Hoisted here (not inside LibraryScreen) so the list position survives opening a novel and coming back.
    val libraryList = rememberLazyListState()
    var verifyBack by rememberSaveable { mutableStateOf("lib") }
    val snack = remember { SnackbarHostState() }
    val saveError by vm.saveError.collectAsState()
    LaunchedEffect(Unit) { vm.messages.collect { snack.showTimed(it, 4_000) } }
    // Tell the user once if the saved library was unreadable and the backup was used instead.
    LaunchedEffect(Unit) { vm.startupNotice()?.let { snack.showTimed(it, 8_000) } }
    // Undo offers share the same snackbar host (shown one after another); tapping Undo restores the old progress.
    LaunchedEffect(Unit) {
        vm.undoOffers.collect { o ->
            val r = snack.showTimed(o.text, 6_000, actionLabel = "Undo", replace = true)
            if (r == SnackbarResult.ActionPerformed) vm.undo(o.id)
        }
    }
    BackHandler(enabled = screen != "lib") {
        screen = when (screen) { "verify" -> verifyBack; "detail" -> detailBack; else -> "lib" }
    }
    fun openFrom(from: String): (String) -> Unit = { uid -> detailBack = from; openUid = uid; screen = "detail" }
    fun verifyFrom(from: String): () -> Unit = { verifyBack = from; screen = "verify" }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                "detail" -> key(openUid) {
                    DetailScreen(vm, openUid, onBack = { screen = detailBack }, onVerify = verifyFrom("detail"), onOpen = { openUid = it }, useQueue = detailBack == "readq")
                }
                "verify" -> VerifyScreen(onDone = { screen = verifyBack })
                "review" -> ReviewScreen(vm, onBack = { screen = "lib" }, onOpen = openFrom("review"))
                "queue" -> QueueScreen(vm, onBack = { screen = "lib" }, onOpen = openFrom("queue"), onVerify = verifyFrom("queue"))
                "checks" -> ChecksScreen(vm, onBack = { screen = "lib" }, onOpen = openFrom("checks"))
                "readq" -> ReadingQueueScreen(vm, onBack = { screen = "lib" }, onOpen = openFrom("readq"))
                else -> LibraryScreen(vm, listState = libraryList, onOpen = openFrom("lib"), go = { screen = it }, onVerify = verifyFrom("lib"))
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
            // Shown on every screen while the last save failed; changes stay in memory and the next save retries everything.
            saveError?.let { msg ->
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                        .padding(start = 12.dp, end = 12.dp, bottom = 84.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp), color = Color(0xFF3B1F1F),
                ) {
                    Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Couldn't save: $msg. Your changes are kept for now.",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Pal.Red,
                        )
                        TextButton(onClick = { vm.retrySave() }) { Text("Retry") }
                    }
                }
            }
        }
    }
}

/** Pick one field and a value, then confirm: the change is applied to every selected novel. */
@Composable
fun BulkEditDialog(count: Int, onDismiss: () -> Unit, onApply: (BulkField, String) -> Unit) {
    var field by remember { mutableStateOf(BulkField.LIKELY_TO_READ) }
    var value by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val plural = if (count == 1) "" else "s"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirming) "Confirm change" else "Edit $count novel$plural") },
        text = {
            if (confirming) Text("Set ${field.label} to \"$value\" on $count novel$plural?")
            else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceKV("Field", BulkField.entries.map { it.name to it.label }, field.name) { field = BulkField.valueOf(it); value = null }
                Choice(field.label, field.options, value ?: "") { value = it }
            }
        },
        confirmButton = {
            val v = value
            TextButton(enabled = v != null, onClick = { if (!confirming) confirming = true else if (v != null) onApply(field, v) }) {
                Text(if (confirming) "Apply" else "Next")
            }
        },
        dismissButton = { TextButton(onClick = { if (confirming) confirming = false else onDismiss() }) { Text(if (confirming) "Back" else "Cancel") } },
    )
}

/**
 * Shows a snackbar that has a ✕ to close it and goes away by itself after [millis]. With [replace] it takes the place of
 * whatever is showing (a newer Undo supersedes an older one) instead of queueing behind it.
 */
private suspend fun SnackbarHostState.showTimed(text: String, millis: Long, actionLabel: String? = null, replace: Boolean = false): SnackbarResult {
    if (replace) currentSnackbarData?.dismiss()
    return coroutineScope {
        // Start timing only once this snackbar is the one on screen (it may be queued behind another), then close it.
        val timer = launch {
            while (currentSnackbarData?.visuals?.message != text) delay(100)
            delay(millis)
            if (currentSnackbarData?.visuals?.message == text) currentSnackbarData?.dismiss()
        }
        try {
            showSnackbar(text, actionLabel, withDismissAction = true, duration = SnackbarDuration.Indefinite)
        } finally {
            timer.cancel()
        }
    }
}

/**
 * Add several novels at once: paste one name or link per line. They are added straight away and the details are filled in
 * by the refresh queue in the background, so the app stays usable; the dialog then lists what was added and skipped.
 */
@Composable
fun AddSeveralDialog(state: BulkAddState, onStart: (String) -> Unit, onDone: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!state.finished) onDone() },
        title = { Text(if (state.finished) "Done" else "Add several novels") },
        text = {
            if (state.finished) Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.report.forEachIndexed { i, line ->
                    Text(line, style = if (i == 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                        color = if (i == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text("One name or link per line") },
                minLines = 6, maxLines = 10, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("A link fills in the title and details from the site, a name is looked up on both sites. Details are fetched in the background. Lines already in your library are skipped.") },
            )
        },
        confirmButton = {
            if (state.finished) TextButton(onClick = onDone) { Text("Done") }
            else TextButton(enabled = text.isNotBlank(), onClick = { onStart(text) }) { Text("Add") }
        },
        dismissButton = { if (!state.finished) TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/**
 * Update the downloaded-through chapter of many novels from text copied out of the Windows app. First a preview of
 * exactly what would change (and what couldn't be matched), then Apply.
 */
@Composable
fun DownloadSyncDialog(vm: NovelViewModel, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var plan by remember { mutableStateOf<DownloadSyncPlan?>(null) }
    var allowLower by remember { mutableStateOf(false) }
    val p = plan
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (p == null) "Update downloads from text" else "Check before applying") },
        text = {
            if (p == null) OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text("Paste the text from the Windows app") },
                minLines = 6, maxLines = 10, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("First line is a header such as Name,Downloaded (optionally Slug and Stable ID). Matched by Stable ID, then page name, then name.") },
            ) else Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${p.raises} go up, ${p.drops} go down, ${p.unchanged} already match", style = MaterialTheme.typography.bodyMedium)
                if (p.drops > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowLower, onCheckedChange = { allowLower = it })
                    Text("Also apply the ${p.drops} lower number${if (p.drops == 1) "" else "s"}", Modifier.clickable { allowLower = !allowLower })
                }
                p.updates.take(12).forEach { u ->
                    val lower = u.to < u.from
                    Text(
                        "${u.name}: ${fmt(u.from)} \u2192 ${fmt(u.to)}" + if (lower) " (lower)" else "",
                        style = MaterialTheme.typography.bodySmall, color = if (lower) Pal.Yellow else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (p.updates.size > 12) Text("\u2026and ${p.updates.size - 12} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (p.notFound.isNotEmpty()) Text(
                    "Not in your library (${p.notFound.size}): ${p.notFound.take(6).joinToString(", ")}${if (p.notFound.size > 6) "\u2026" else ""}",
                    style = MaterialTheme.typography.bodySmall, color = Pal.Yellow,
                )
                if (p.badLines.isNotEmpty()) Text(
                    "Couldn't read ${p.badLines.size} line${if (p.badLines.size == 1) "" else "s"}: ${p.badLines.first()}",
                    style = MaterialTheme.typography.bodySmall, color = Pal.Red,
                )
            }
        },
        confirmButton = {
            if (p == null) TextButton(enabled = text.isNotBlank(), onClick = { plan = vm.planDownloadSync(text) }) { Text("Preview") }
            else {
                val apply = p.updates.filter { allowLower || it.to > it.from }
                TextButton(enabled = apply.isNotEmpty(), onClick = { vm.applyDownloadSync(apply); onDismiss() }) { Text("Apply (${apply.size})") }
            }
        },
        dismissButton = { TextButton(onClick = { if (p == null) onDismiss() else plan = null }) { Text(if (p == null) "Cancel" else "Back") } },
    )
}

/** Change a novel's title. Empty titles and titles another novel already has are refused, with the reason shown. */
@Composable
fun RenameDialog(current: String, problem: (String) -> String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var t by remember { mutableStateOf(current) }
    val trimmed = t.trim()
    val changed = trimmed != current
    val issue = if (changed || trimmed.isEmpty()) problem(trimmed) else null
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = t, onValueChange = { t = it.replace("\n", " ") }, singleLine = true, label = { Text("Title") },
                isError = issue != null, supportingText = { if (issue != null) Text(issue) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(enabled = changed && issue == null, onClick = { onSave(trimmed); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ filters
@Composable
fun RemovableChip(text: String, onRemove: () -> Unit) = FilterChip(
    selected = true, onClick = onRemove, label = { Text(text) },
    trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove filter", modifier = Modifier.size(16.dp)) },
)

/** Several values of one group can be on at once (OR within the group). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiChoice(label: String, options: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> FilterChip(selected = o in selected, onClick = { onToggle(o) }, label = { Text(o) }) }
        }
    }
}

/**
 * Top row: Filters (opens the panel), quick toggles that drive the same filter state, a removable chip per other
 * active filter, and Clear all. State lives in the ViewModel, so it survives opening a novel and coming back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterBar(vm: NovelViewModel) {
    val f by vm.filters.collectAsState()
    val genreOpts by vm.genreOptions.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    fun set(g: (FilterState) -> FilterState) = vm.filters.update(g)
    Column {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = open, onClick = { open = !open }, label = { Text(if (f.isDefault) "Filters" else "Filters (${f.activeCount})") })
            FilterChip(selected = f.ready, onClick = { set { it.copy(ready = !it.ready) } }, label = { Text("Ready to read") })
            FilterChip(
                selected = f.converted == Tri.YES, label = { Text("Converted") },
                onClick = { set { it.copy(converted = if (it.converted == Tri.YES) Tri.ALL else Tri.YES) } },
            )
            FilterChip(
                selected = f.sent == Tri.YES, label = { Text("Sent") },
                onClick = { set { it.copy(sent = if (it.sent == Tri.YES) Tri.ALL else Tri.YES) } },
            )
            FilterChip(
                selected = f.download == Tri.YES, label = { Text("Needs download") },
                onClick = { set { it.copy(download = if (it.download == Tri.YES) Tri.ALL else Tri.YES) } },
            )
            FilterChip(
                selected = f.review == Tri.YES, label = { Text("Needs review") },
                onClick = { set { it.copy(review = if (it.review == Tri.YES) Tri.ALL else Tri.YES) } },
            )
            FilterChip(
                selected = f.caughtUp == Tri.YES, label = { Text("Caught up") },
                onClick = { set { it.copy(caughtUp = if (it.caughtUp == Tri.YES) Tri.ALL else Tri.YES) } },
            )
            f.reading.forEach { v -> RemovableChip("Reading: $v") { set { it.copy(reading = it.reading - v) } } }
            f.novel.forEach { v -> RemovableChip("Novel: $v") { set { it.copy(novel = it.novel - v) } } }
            f.likely.forEach { v -> RemovableChip("Likely: $v") { set { it.copy(likely = it.likely - v) } } }
            f.genres.forEach { g -> RemovableChip("Genre: ${titleCase(g)}") { set { it.copy(genres = it.genres - g) } } }
            if (f.converted == Tri.NO) RemovableChip("Converted: No") { set { it.copy(converted = Tri.ALL) } }
            if (f.sent == Tri.NO) RemovableChip("Sent: No") { set { it.copy(sent = Tri.ALL) } }
            if (f.download == Tri.NO) RemovableChip("Needs download: No") { set { it.copy(download = Tri.ALL) } }
            if (f.review == Tri.NO) RemovableChip("Needs review: No") { set { it.copy(review = Tri.ALL) } }
            if (f.caughtUp == Tri.NO) RemovableChip("Caught up: No") { set { it.copy(caughtUp = Tri.ALL) } }
            if (!f.isDefault) TextButton(onClick = { vm.clearFilters() }) { Text("Clear all") }
        }
        if (open) {
            // Back closes the panel first (instead of leaving the app). The panel is at most half the screen and scrolls.
            BackHandler { open = false }
            val maxPanel = (LocalConfiguration.current.screenHeightDp / 2).dp
            Column(
                Modifier.heightIn(max = maxPanel).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MultiChoice("Reading status", READING, f.reading) { v -> set { it.copy(reading = it.reading.toggled(v)) } }
                MultiChoice("Novel status", NOVEL_STATUS, f.novel) { v -> set { it.copy(novel = it.novel.toggled(v)) } }
                MultiChoice("Likely to read", LIKELY, f.likely) { v -> set { it.copy(likely = it.likely.toggled(v)) } }
                ChoiceKV("Converted", Tri.entries.map { it.name to it.label }, f.converted.name) { v -> set { it.copy(converted = Tri.valueOf(v)) } }
                ChoiceKV("Sent", Tri.entries.map { it.name to it.label }, f.sent.name) { v -> set { it.copy(sent = Tri.valueOf(v)) } }
                if (genreOpts.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Genre (any of the selected)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            genreOpts.forEach { (g, count) ->
                                FilterChip(selected = g in f.genres, onClick = { set { it.copy(genres = it.genres.toggled(g)) } }, label = { Text("${titleCase(g)} ($count)") })
                            }
                        }
                    }
                }
                ChoiceKV("Needs download", Tri.entries.map { it.name to it.label }, f.download.name) { v -> set { it.copy(download = Tri.valueOf(v)) } }
                ChoiceKV("Needs review", Tri.entries.map { it.name to it.label }, f.review.name) { v -> set { it.copy(review = Tri.valueOf(v)) } }
                ChoiceKV("Caught up (chapters read = latest chapter)", Tri.entries.map { it.name to it.label }, f.caughtUp.name) { v -> set { it.copy(caughtUp = Tri.valueOf(v)) } }
            }
        }
    }
}

// ------------------------------------------------------------------ library
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: NovelViewModel, listState: LazyListState, onOpen: (String) -> Unit, go: (String) -> Unit, onVerify: () -> Unit) {
    val novels by vm.shown.collectAsState()
    val all by vm.all.collectAsState()
    val sort by vm.sort.collectAsState()
    val readingQ by vm.readingQueue.collectAsState()
    // Multi-select: long-press a card to start, tap to add/remove, then Edit applies one field to all of them.
    var selected by remember { mutableStateOf(setOf<String>()) }
    var bulkOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    if (bulkOpen) BulkEditDialog(
        count = selected.size, onDismiss = { bulkOpen = false },
        onApply = { field, value -> vm.bulkSet(selected, field, value); selected = emptySet(); bulkOpen = false },
    )
    val queue by vm.queue.collectAsState()
    val plan by vm.importPlan.collectAsState()
    val warnUids by vm.warnUids.collectAsState()
    // The ViewModel owns the query; the text box just shows it. (A local rememberSaveable copy was lost
    // whenever the library left composition, while the ViewModel kept filtering by the old text.)
    val q by vm.query.collectAsState()
    val searchScope by vm.searchScope.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var showBulk by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    val bulk by vm.bulkAdd.collectAsState()
    var showRefreshAll by remember { mutableStateOf(false) }
    var progressFor by remember { mutableStateOf<String?>(null) }
    var sortOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val pendingCount = all.count { it.pendingCount > 0 }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.readForImport(uri) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if (uri != null) vm.export(uri) }

    val view = LocalView.current
    DisposableEffect(queue.running) {
        view.keepScreenOn = queue.running
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (selected.isNotEmpty()) TopAppBar(
                title = { Text("${selected.size} selected") },
                navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, contentDescription = "Cancel selection") } },
                actions = {
                    TextButton(onClick = { selected = novels.map { it.uid }.toSet() }) { Text("Select all") }
                    TextButton(onClick = { bulkOpen = true }) { Text("Edit") }
                },
            ) else TopAppBar(
                title = { Text("Novels (${novels.size}/${all.size})") },
                actions = {
                    Box {
                        IconButton(onClick = { sortOpen = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort") }
                        DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                            SortBy.entries.forEach { s ->
                                DropdownMenuItem(text = { Text(if (s == sort) "✓  ${s.label}" else s.label) }, onClick = { vm.sort.value = s; sortOpen = false })
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { moreOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            DropdownMenuItem(text = { Text("Import CSV…") }, onClick = { moreOpen = false; importLauncher.launch(arrayOf("*/*")) })
                            DropdownMenuItem(text = { Text("Export CSV…") }, onClick = { moreOpen = false; exportLauncher.launch("NovelStatus_export.csv") })
                            DropdownMenuItem(text = { Text("Add several\u2026") }, onClick = { moreOpen = false; showBulk = true })
                            DropdownMenuItem(text = { Text("Update downloads from text\u2026") }, onClick = { moreOpen = false; showSync = true })
                            DropdownMenuItem(text = { Text("Next to read") }, onClick = { moreOpen = false; vm.nextToRead()?.let { onOpen(it.uid) } })
                            DropdownMenuItem(text = { Text("Reading queue (${readingQ.size})") }, onClick = { moreOpen = false; go("readq") })
                            DropdownMenuItem(text = { Text("Review counts ($pendingCount)") }, onClick = { moreOpen = false; go("review") })
                            DropdownMenuItem(text = { Text("Data check (${warnUids.size})") }, onClick = { moreOpen = false; go("checks") })
                            DropdownMenuItem(text = { Text("Refresh queue") }, onClick = { moreOpen = false; go("queue") })
                            DropdownMenuItem(text = { Text("Refresh all links…") }, onClick = { moreOpen = false; showRefreshAll = true })
                            DropdownMenuItem(text = { Text("Verify in browser") }, onClick = { moreOpen = false; onVerify() })
                        }
                    }
                },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, contentDescription = "Add novel") } },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                value = q, onValueChange = { vm.query.value = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                // Titles only by default, so a word in a description doesn't bring in unrelated novels. Tap to widen.
                // The small ✕ at the right clears the text.
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.searchScope.value = if (searchScope == SearchScope.TITLES) SearchScope.ALL else SearchScope.TITLES }) { Text(searchScope.label) }
                        if (q.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
                    }
                },
                placeholder = { Text(if (searchScope == SearchScope.TITLES) "Search titles…" else "Search titles, genre, author, remarks, description…") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            FilterBar(vm)
            if (queue.items.isNotEmpty()) {
                val done = queue.count(QStatus.DONE) + queue.count(QStatus.FAILED)
                Card(onClick = { go("queue") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val state = when {
                            queue.running -> "Refreshing"
                            queue.count(QStatus.BLOCKED) > 0 -> "Waiting for bot check"
                            queue.count(QStatus.PENDING) > 0 -> "Paused"
                            else -> "Finished"
                        }
                        Text("$state: $done/${queue.items.size}" + if (queue.count(QStatus.FAILED) > 0) " (${queue.count(QStatus.FAILED)} failed)" else "  ·  tap for queue")
                        LinearProgressIndicator(progress = { done / queue.items.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (novels.isEmpty()) {
                Text(
                    if (all.isEmpty()) "No novels yet. Use ⋮ → Import CSV, or tap + to add one." else "Nothing matches.",
                    modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(novels, key = { it.uid }) { n ->
                    NovelCard(
                        n, warn = n.uid in warnUids, selected = n.uid in selected, selecting = selected.isNotEmpty(),
                        onOpen = { if (selected.isNotEmpty()) selected = selected.toggled(n.uid) else onOpen(n.uid) },
                        onLongPress = { selected = selected.toggled(n.uid) },
                        onProgress = { progressFor = n.uid },
                    )
                }
            }
        }
    }

    if (showAdd) AddDialog(onDismiss = { showAdd = false }, onAdd = { n, l -> vm.add(n, l) })
    if (showBulk || bulk.finished) AddSeveralDialog(
        bulk, onStart = { vm.startBulkAdd(it) },
        onDone = { showBulk = false; vm.clearBulkAdd() },
    )
    if (showSync) DownloadSyncDialog(vm, onDismiss = { showSync = false })
    progressFor?.let { uid ->
        vm.novel(uid)?.let { n -> ProgressDialog(n, onDismiss = { progressFor = null }, onSave = { vm.setRead(uid, it) }) }
    }
    if (showRefreshAll) RefreshAllDialog(all, onDismiss = { showRefreshAll = false }, onStart = { skip, bg -> if (bg) vm.startRefreshAllInBackground(skip) else vm.startRefreshAll(skip); go("queue") })
    plan?.let { p -> ImportDialog(p, onCancel = { vm.cancelImport() }, onApply = { vm.applyImport(it) }) }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun NovelCard(
    n: Novel, warn: Boolean, onOpen: () -> Unit, onProgress: () -> Unit,
    selected: Boolean = false, selecting: Boolean = false, onLongPress: () -> Unit = {},
) {
    Card(
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Cover(n.coverUrl, Modifier.width(56.dp).height(84.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(n.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${fmt(n.readCount)} / ${fmt(n.currentCount)} read", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Downloaded ${fmt(n.downloadedThrough)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (n.readyToRead > 0) Text("· ${fmt(n.readyToRead)} ready", style = MaterialTheme.typography.bodySmall, color = Pal.Blue)
                    else if (n.toDownload > 0 && n.likely != "No") Text("· ${fmt(n.toDownload)} to download", style = MaterialTheme.typography.bodySmall, color = Pal.Yellow)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tag(n.readingStatus, statusColor(n.readingStatus))
                    Tag(n.novelStatus, statusColor(n.novelStatus))
                    Tag("Likely ${n.likely}", statusColor(n.likely))
                    if (n.pendingCount > 0) Tag("Review count", Pal.Yellow)
                    if (warn) Tag("Check", Pal.Red)
                    if (n.manualChapters) Tag("Manual", Color.Gray)
                    n.freshnessTag()?.let { f -> Tag(f.label, when (f.level) { 2 -> Pal.Red; 1 -> Pal.Yellow; else -> Color.Gray }) }
                }
            }
            // Ring above Update fills the right-hand side, so the card has no empty corner.
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ProgressRing(n.progress(), centerText = fmt(n.readCount))
                TextButton(enabled = !selecting, onClick = onProgress) { Text("Update") }
            }
        }
    }
}

// ------------------------------------------------------------------ detail
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(vm: NovelViewModel, uid: String, onBack: () -> Unit, onVerify: () -> Unit, onOpen: (String) -> Unit, useQueue: Boolean = false) {
    val all by vm.all.collectAsState()
    val busy by vm.busy.collectAsState()
    val ctx = LocalContext.current
    val n = all.firstOrNull { it.uid == uid }
    if (n == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var progress by remember { mutableStateOf(false) }
    var editNum by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showSimilar by remember { mutableStateOf(false) }
    var remarks by remember(n.uid) { mutableStateOf(n.remarks) }
    var link by remember(n.uid) { mutableStateOf(n.slug) }
    var expanded by remember { mutableStateOf(false) }
    // Previous / next novel follow the list you came from: the library as currently searched, filtered and sorted
    // (or the reading queue when opened from it). If this novel drops out of that list after an edit, the arrows
    // keep working from the place it was.
    val navList by (if (useQueue) vm.readingQueue else vm.shown).collectAsState()
    var anchor by remember { mutableStateOf(navList.indexOfFirst { it.uid == uid }) }
    val here = navList.indexOfFirst { it.uid == uid }
    LaunchedEffect(here) { if (here >= 0) anchor = here }
    val prevNovel = if (here >= 0) navList.getOrNull(here - 1) else navList.getOrNull(anchor - 1)
    val nextNovel = if (here >= 0) navList.getOrNull(here + 1) else navList.getOrNull(anchor)
    val checks = n.issues()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(n.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, contentDescription = "Rename") }
                    TextButton(onClick = { if (n.queuePos > 0) vm.removeFromReadingQueue(uid) else vm.addToReadingQueue(uid) }) {
                        Text(if (n.queuePos > 0) "Unqueue" else "Queue")
                    }
                    IconButton(enabled = n.slug.isNotBlank() && busy == null, onClick = { vm.refreshOne(uid) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh from site")
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Pal.Red) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (prevNovel != null || nextNovel != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = prevNovel != null, onClick = { prevNovel?.let { onOpen(it.uid) } }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous novel")
                }
                Text(
                    if (here >= 0) "${here + 1} of ${navList.size}" else "", Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(enabled = nextNovel != null, onClick = { nextNovel?.let { onOpen(it.uid) } }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next novel")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Cover(n.coverUrl, Modifier.width(96.dp).height(136.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(n.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable { renaming = true })
                    if (n.author.isNotBlank()) Text(n.author, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val rating = listOf(n.ratingCom to ".com", n.ratingNet to ".net").filter { it.first.isNotBlank() }.joinToString("   ") { "${it.first} ${it.second}" }
                    if (rating.isNotBlank()) Text(rating, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (n.busyHint(busy)) Text("Refreshing…", color = Pal.Blue, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Description first, right under the title, so it can be read quickly while moving from novel to novel.
            if (n.genres.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                n.genres.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { Tag(it, MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (n.altNames.isNotBlank()) Text("Also known as: ${n.altNames}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (n.description.isNotBlank()) {
                Text(n.description, maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show more") }
            }

            if (n.pendingCount > 0) PendingCard(n, vm, onOpen = null)

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StackBar(n.progress(), height = 12)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("Read", n.readCount, Pal.Green) { progress = true }
                        Stat("Downloaded through", n.downloadedThrough, Pal.Blue) { editNum = "downloaded" }
                        Stat(if (n.manualChapters) "Latest (manual)" else "Latest", n.currentCount, MaterialTheme.colorScheme.onSurface) { editNum = "current" }
                    }
                    Button(onClick = { vm.markAllDownloadedRead(uid) }, modifier = Modifier.fillMaxWidth()) { Text("Mark all downloaded chapters as read") }
                    Text("Sets read to your downloaded-through chapter (${fmt(n.downloadedThrough)}).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (n.toDownload > 0) {
                        Text("To download: chapters ${fmt(n.downloadedThrough + 1)}–${fmt(n.currentCount)} (${fmt(n.toDownload)})", color = Pal.Yellow)
                    }
                    Text("Unread total ${fmt(n.unread)}  ·  ready ${fmt(n.readyToRead)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (checks.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Data checks", style = MaterialTheme.typography.titleSmall)
                        checks.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = if (c.severity == Severity.WARN) Pal.Red else Pal.Yellow)
                                Text(c.message, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { vm.dismissIssue(uid, c.code) }) { Text("Dismiss") }
                            }
                        }
                    }
                }
            }

            Choice("Reading status", READING, n.readingStatus) { v -> vm.update(uid) { it.copy(readingStatus = v) } }
            Choice("Novel status (yours)", NOVEL_STATUS, n.novelStatus) { v -> vm.update(uid) { it.copy(novelStatus = v) } }
            if (n.siteStatus.isNotBlank()) {
                Text(
                    "Site says: ${n.siteStatus}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (n.siteStatus in NOVEL_STATUS && n.siteStatus != n.novelStatus) Pal.Yellow else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Choice("Likely to read", LIKELY, n.likely) { v -> vm.update(uid) { it.copy(likely = v) } }
            SwitchRow("Converted", checked = n.conversion) { v -> vm.update(uid) { it.copy(conversion = v) } }
            SwitchRow("Sent", checked = n.sent) { v -> vm.update(uid) { it.copy(sent = v) } }
            SwitchRow(
                "Set chapters manually", "Scraper skips the chapter count (use for titles like “MW Chapter 5”). Tap Latest to set it.",
                checked = n.manualChapters,
            ) { v -> vm.update(uid) { it.copy(manualChapters = v, pendingCount = if (v) 0 else it.pendingCount, pendingReason = if (v) "" else it.pendingReason) } }
            ChoiceKV(
                "Preferred source for the chapter count",
                listOf("" to "Auto") + BASES.keys.map { it to ".$it" },
                n.preferredSource,
            ) { v -> vm.update(uid) { it.copy(preferredSource = v) } }

            HorizontalDivider()
            OutlinedTextField(remarks, { remarks = it }, label = { Text("Remarks") }, modifier = Modifier.fillMaxWidth())
            if (remarks != n.remarks) Button(onClick = { vm.update(uid) { it.copy(remarks = remarks) } }) { Text("Save remarks") }

            Text("Site link", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                link, { link = it }, label = { Text("Slug or URL (e.g. spirit-realm.html)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Found on: ${n.sites.ifBlank { "unknown (tries all sources)" }}") },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (slugFrom(link) != n.slug) Button(onClick = { vm.update(uid) { it.copy(slug = slugFrom(link)) } }) { Text("Save link") }
                OutlinedButton(enabled = n.slug.isNotBlank() && busy == null, onClick = { vm.refreshOne(uid) }) {
                    Text(if (busy == uid) "Refreshing…" else "Refresh from site")
                }
                BASES.forEach { (key, base) ->
                    OutlinedButton(enabled = n.slug.isNotBlank(), onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/${n.slug}.html")))
                    }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        Text(" .$key")
                    }
                }
                OutlinedButton(onClick = onVerify) { Icon(Icons.Default.Public, contentDescription = null); Text(" Verify") }
            }

            HorizontalDivider()
            val counts = n.siteCountMap()
            Text(
                buildString {
                    append("Last read: ${whenText(n.lastReadAt)}")
                    append("\nLast successful refresh: ${whenText(n.lastRefreshAt)}${if (n.lastRefreshAt > 0) " (${agoText(n.lastRefreshAt)})" else ""}")
                    if (n.lastAttemptAt > n.lastRefreshAt) append("\nLast attempt (failed): ${whenText(n.lastAttemptAt)}")
                    if (counts.isNotEmpty()) append("\nSite counts: " + counts.entries.joinToString("  ") { "${sourceLabel(it.key)} ${fmt(it.value)}" })
                    if (n.scrapeNote.isNotBlank()) append("\n${n.scrapeNote}")
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Scrape History (diagnostic): newest first, one line per observation.
            val history = remember(n.scrapeHistory) { parseHistory(n.scrapeHistory).first }
            if (history.isNotEmpty()) {
                TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "Hide scrape history" else "Scrape history (${history.size})") }
                if (showHistory) Text(
                    history.joinToString("\n") { e ->
                        val outcome = when (e.outcome) {
                            "APPLIED" -> "count updated"; "PENDING" -> "needs review"; "MANUAL" -> "manual novel, count skipped"; else -> "no change"
                        }
                        "${whenText(e.at)} · ${sourceLabel(e.source)} · ${fmt(e.count)} ch · ${e.siteStatus.ifBlank { "status n/a" }} · $outcome"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Closest matches by genre: shared genres out of all genres of both, best first.
            val similar = remember(n.genres, all) { vm.similarByGenre(uid) }
            if (similar.isNotEmpty()) {
                TextButton(onClick = { showSimilar = !showSimilar }) { Text(if (showSimilar) "Hide similar novels" else "Similar by genre (${similar.size})") }
                if (showSimilar) similar.forEach { s ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(s.novel.uid) }.defaultMinSize(minHeight = 48.dp).padding(vertical = 6.dp)) {
                        Text(s.novel.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${s.percent}% match \u00b7 ${s.shared.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (renaming) RenameDialog(n.name, problem = { vm.renameProblem(uid, it) }, onDismiss = { renaming = false }, onSave = { vm.rename(uid, it) })
    if (progress) ProgressDialog(n, onDismiss = { progress = false }, onSave = { vm.setRead(uid, it) })
    when (editNum) {
        "downloaded" -> NumberDialog(
            "Downloaded through chapter", n.downloadedThrough, { editNum = null },
            warnAbove = n.currentCount,
            warnText = "That is past the latest known chapter (${fmt(n.currentCount)}). Save anyway?",
        ) { v -> vm.setDownloaded(uid, v) }
        "current" -> NumberDialog(
            "Latest chapter count", n.currentCount, { editNum = null },
            warnBelow = maxOf(n.readCount, n.downloadedThrough),
            warnBelowText = "That is below your read (${fmt(n.readCount)}) or downloaded (${fmt(n.downloadedThrough)}) count. Save anyway?",
        ) { v -> vm.setCurrent(uid, v) }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${n.name}?") },
            text = { Text("This removes it from the app. Your CSV files are not affected.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(uid) }) { Text("Delete", color = Pal.Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun Novel.busyHint(busy: String?) = busy == uid

// ------------------------------------------------------------------ manual verification browser
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyScreen(onDone: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Verify in browser") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    BASES.forEach { (key, base) -> TextButton(onClick = { web?.loadUrl(base) }) { Text(".$key") } }
                    TextButton(onClick = onDone) { Text("Done") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Text(
                "Solve any “verify you are human” check, then tap Done. Refreshing and cover images reuse this session.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { c ->
                    WebView(c).apply {
                        applyScrapeSettings(blockImages = false)
                        webViewClient = WebViewClient()
                        CookieManager.getInstance().setAcceptCookie(true)
                        loadUrl(BASES.values.first())
                        web = this
                    }
                },
            )
        }
    }
}