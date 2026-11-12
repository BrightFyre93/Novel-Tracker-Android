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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.update

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
 * The centre shows the downloaded-through count; exact counts are shown as text next to it.
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

// ------------------------------------------------------------------ dialogs
/**
 * Whole-number editor. Blank/invalid input can't be saved; a value above [warnAbove] (when > 0) needs a second,
 * explicit tap ("Save anyway") instead of being silently accepted or clamped. Input is kept until the dialog closes.
 */
@Composable
fun NumberDialog(
    title: String, initial: Int, onDismiss: () -> Unit,
    warnAbove: Int = 0, warnText: String = "",
    warnBelow: Int = 0, warnBelowText: String = "", onSave: (Int) -> Unit,
) {
    var t by remember { mutableStateOf(initial.toString()) }
    var confirming by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    val v = t.toIntOrNull()
    val tooHigh = v != null && warnAbove > 0 && v > warnAbove
    val tooLow = v != null && warnBelow > 0 && v < warnBelow
    val over = tooHigh || tooLow
    val message = if (tooHigh) warnText else warnBelowText
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = t, onValueChange = { t = it.filter(Char::isDigit); confirming = false }, singleLine = true,
                    isError = v == null || (over && confirming),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (v == null) Text("Enter a whole number", style = MaterialTheme.typography.bodySmall, color = Pal.Red)
                else if (over && confirming) Text(message, style = MaterialTheme.typography.bodySmall, color = Pal.Yellow)
            }
        },
        confirmButton = {
            TextButton(enabled = v != null && !submitted, onClick = {
                if (v == null) return@TextButton
                if (over && !confirming) confirming = true
                else { submitted = true; onSave(v); onDismiss() }
            }) { Text(if (over && confirming) "Save anyway" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ProgressDialog(n: Novel, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var t by remember { mutableStateOf(n.readCount.toString()) }
    var confirming by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    val v = t.toIntOrNull()
    val cur = v ?: 0
    // A read count past the latest known chapter is possible (site count may be stale) but must be confirmed.
    val over = v != null && n.currentCount > 0 && v > n.currentCount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(n.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = t, onValueChange = { t = it.filter(Char::isDigit); confirming = false }, singleLine = true,
                    label = { Text("Chapters read") },
                    isError = v == null || (over && confirming),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (v == null) Text("Enter a whole number", style = MaterialTheme.typography.bodySmall, color = Pal.Red)
                else if (over && confirming) Text(
                    "Chapter ${fmt(v)} is past the latest known chapter (${fmt(n.currentCount)}). Save anyway?",
                    style = MaterialTheme.typography.bodySmall, color = Pal.Yellow,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 10, 50).forEach { d -> AssistChip(onClick = { t = (cur + d).toString(); confirming = false }, label = { Text("+$d") }) }
                }
                Text(
                    "Downloaded through ${fmt(n.downloadedThrough)}  ·  latest ${fmt(n.currentCount)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (n.downloadedThrough > cur) {
                    OutlinedButton(onClick = { t = n.downloadedThrough.toString(); confirming = false }) { Text("Set to downloaded (${fmt(n.downloadedThrough)})") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = v != null && !submitted, onClick = {
                if (v == null) return@TextButton
                if (over && !confirming) confirming = true
                else { submitted = true; onSave(v); onDismiss() }
            }) { Text(if (over && confirming) "Save anyway" else "Save") }
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
                    supportingText = { Text("Filled from the site automatically when a link is given") },
                )
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onAdd(name, link); onDismiss() }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RefreshAllDialog(all: List<Novel>, onDismiss: () -> Unit, onStart: (Boolean) -> Unit) {
    var skip by remember { mutableStateOf(true) }
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
            }
        },
        confirmButton = { TextButton(onClick = { onStart(skip); onDismiss() }) { Text("Start") } },
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
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
    BackHandler(enabled = screen != "lib") {
        screen = when (screen) { "verify" -> verifyBack; "detail" -> detailBack; else -> "lib" }
    }
    fun openFrom(from: String): (String) -> Unit = { uid -> detailBack = from; openUid = uid; screen = "detail" }
    fun verifyFrom(from: String): () -> Unit = { verifyBack = from; screen = "verify" }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                "detail" -> DetailScreen(vm, openUid, onBack = { screen = detailBack }, onVerify = verifyFrom("detail"))
                "verify" -> VerifyScreen(onDone = { screen = verifyBack })
                "review" -> ReviewScreen(vm, onBack = { screen = "lib" }, onOpen = openFrom("review"))
                "queue" -> QueueScreen(vm, onBack = { screen = "lib" }, onOpen = openFrom("queue"), onVerify = verifyFrom("queue"))
                "checks" -> ChecksScreen(vm, onBack = { screen = "lib" }, onOpen = openFrom("checks"))
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
@Composable
fun FilterBar(vm: NovelViewModel) {
    val f by vm.filters.collectAsState()
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
                selected = f.download == Tri.YES, label = { Text("Needs download") },
                onClick = { set { it.copy(download = if (it.download == Tri.YES) Tri.ALL else Tri.YES) } },
            )
            FilterChip(
                selected = f.review == Tri.YES, label = { Text("Needs review") },
                onClick = { set { it.copy(review = if (it.review == Tri.YES) Tri.ALL else Tri.YES) } },
            )
            f.reading.forEach { v -> RemovableChip("Reading: $v") { set { it.copy(reading = it.reading - v) } } }
            f.novel.forEach { v -> RemovableChip("Novel: $v") { set { it.copy(novel = it.novel - v) } } }
            f.likely.forEach { v -> RemovableChip("Likely: $v") { set { it.copy(likely = it.likely - v) } } }
            if (f.download == Tri.NO) RemovableChip("Needs download: No") { set { it.copy(download = Tri.ALL) } }
            if (f.review == Tri.NO) RemovableChip("Needs review: No") { set { it.copy(review = Tri.ALL) } }
            if (!f.isDefault) TextButton(onClick = { vm.clearFilters() }) { Text("Clear all") }
        }
        if (open) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MultiChoice("Reading status", READING, f.reading) { v -> set { it.copy(reading = it.reading.toggled(v)) } }
                MultiChoice("Novel status", NOVEL_STATUS, f.novel) { v -> set { it.copy(novel = it.novel.toggled(v)) } }
                MultiChoice("Likely to read", LIKELY, f.likely) { v -> set { it.copy(likely = it.likely.toggled(v)) } }
                ChoiceKV("Needs download", Tri.entries.map { it.name to it.label }, f.download.name) { v -> set { it.copy(download = Tri.valueOf(v)) } }
                ChoiceKV("Needs review", Tri.entries.map { it.name to it.label }, f.review.name) { v -> set { it.copy(review = Tri.valueOf(v)) } }
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
    val queue by vm.queue.collectAsState()
    val plan by vm.importPlan.collectAsState()
    val warnUids by vm.warnUids.collectAsState()
    // The ViewModel owns the query; the text box just shows it. (A local rememberSaveable copy was lost
    // whenever the library left composition, while the ViewModel kept filtering by the old text.)
    val q by vm.query.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
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
            TopAppBar(
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
                placeholder = { Text("Search name, genre, author, remarks…") },
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
                    NovelCard(n, warn = n.uid in warnUids, onOpen = { onOpen(n.uid) }, onProgress = { progressFor = n.uid })
                }
            }
        }
    }

    if (showAdd) AddDialog(onDismiss = { showAdd = false }, onAdd = { n, l -> vm.add(n, l) })
    progressFor?.let { uid ->
        vm.novel(uid)?.let { n -> ProgressDialog(n, onDismiss = { progressFor = null }, onSave = { vm.setRead(uid, it) }) }
    }
    if (showRefreshAll) RefreshAllDialog(all, onDismiss = { showRefreshAll = false }, onStart = { skip -> vm.startRefreshAll(skip); go("queue") })
    plan?.let { p -> ImportDialog(p, onCancel = { vm.cancelImport() }, onApply = { vm.applyImport(it) }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NovelCard(n: Novel, warn: Boolean, onOpen: () -> Unit, onProgress: () -> Unit) {
    Card(onClick = onOpen, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Cover(n.coverUrl, Modifier.width(56.dp).height(84.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(n.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProgressRing(n.progress(), centerText = fmt(n.downloadedThrough))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${fmt(n.readCount)} / ${fmt(n.currentCount)} read", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (n.readyToRead > 0) Text("${fmt(n.readyToRead)} ready", style = MaterialTheme.typography.bodySmall, color = Pal.Blue)
                        else if (n.toDownload > 0 && n.likely != "No") Text("${fmt(n.toDownload)} to download", style = MaterialTheme.typography.bodySmall, color = Pal.Yellow)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Tag(n.readingStatus, statusColor(n.readingStatus))
                        Tag(n.novelStatus, statusColor(n.novelStatus))
                        Tag("Likely ${n.likely}", statusColor(n.likely))
                        if (n.pendingCount > 0) Tag("Review count", Pal.Yellow)
                        if (warn) Tag("Check", Pal.Red)
                        if (n.manualChapters) Tag("Manual", Color.Gray)
                    }
                    TextButton(onClick = onProgress) { Text("Update") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ detail
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(vm: NovelViewModel, uid: String, onBack: () -> Unit, onVerify: () -> Unit) {
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
    var remarks by remember(n.uid) { mutableStateOf(n.remarks) }
    var link by remember(n.uid) { mutableStateOf(n.slug) }
    var expanded by remember { mutableStateOf(false) }
    val checks = n.issues()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(n.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(enabled = n.slug.isNotBlank() && busy == null, onClick = { vm.refreshOne(uid) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh from site")
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Pal.Red) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Cover(n.coverUrl, Modifier.width(96.dp).height(136.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(n.name, style = MaterialTheme.typography.titleLarge)
                    if (n.author.isNotBlank()) Text(n.author, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val rating = listOf(n.ratingCom to ".com", n.ratingNet to ".net").filter { it.first.isNotBlank() }.joinToString("   ") { "${it.first} ${it.second}" }
                    if (rating.isNotBlank()) Text(rating, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (n.busyHint(busy)) Text("Refreshing…", color = Pal.Blue, style = MaterialTheme.typography.bodySmall)
                }
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
            if (n.genres.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                n.genres.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { Tag(it, MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (n.altNames.isNotBlank()) Text("Also known as: ${n.altNames}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (n.description.isNotBlank()) {
                Text(n.description, maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show more") }
            }
            val counts = n.siteCountMap()
            Text(
                buildString {
                    append("Last read: ${whenText(n.lastReadAt)}")
                    append("\nLast successful refresh: ${whenText(n.lastRefreshAt)}")
                    if (n.lastAttemptAt > n.lastRefreshAt) append("\nLast attempt (failed): ${whenText(n.lastAttemptAt)}")
                    if (counts.isNotEmpty()) append("\nSite counts: " + counts.entries.joinToString("  ") { "${sourceLabel(it.key)} ${fmt(it.value)}" })
                    if (n.scrapeNote.isNotBlank()) append("\n${n.scrapeNote}")
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

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