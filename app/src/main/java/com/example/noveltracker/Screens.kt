package com.example.noveltracker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ------------------------------------------------------------------ import preview
@Composable
fun ImportDialog(p: ImportPlan, onCancel: () -> Unit, onApply: (ImportMode) -> Unit) {
    var mode by remember { mutableStateOf(ImportMode.MERGE) }
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Import preview") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${p.result.novels.size} rows read: ${p.newRows} new, ${p.matched} already in your library.")
                if (p.matched > 0) Text("Of the matches: ${p.csvNewer} are newer in the file, ${p.keptYours} would keep yours.", style = MaterialTheme.typography.bodySmall, color = sub)
                if (p.matchedByName > 0) Text("${p.matchedByName} matched by name (different Stable ID).", style = MaterialTheme.typography.bodySmall, color = sub)
                p.result.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = sub) }
                if (p.result.issues.isNotEmpty()) {
                    val byKind = p.result.issues.groupingBy { it.kind }.eachCount().entries.sortedBy { it.key }
                    Text("${p.result.issues.size} problems in the file: " + byKind.joinToString(" · ") { "${it.value} ${it.key.label.lowercase()}" }, color = Pal.Yellow)
                    p.result.issues.sortedBy { it.kind }.take(6).forEach { i ->
                        Column {
                            Text("Row ${i.row}${if (i.name.isNotEmpty()) " (${i.name})" else ""} · ${i.kind.label}", style = MaterialTheme.typography.bodySmall)
                            Text(i.message, style = MaterialTheme.typography.bodySmall, color = sub)
                        }
                    }
                    if (p.result.issues.size > 6) Text("…and ${p.result.issues.size - 6} more", style = MaterialTheme.typography.bodySmall, color = sub)
                }
                if (p.warnings.isNotEmpty()) {
                    Text("Data checks the imported rows would raise:", color = Pal.Yellow)
                    p.warnings.forEach { (code, k) -> Text("• ${ISSUE_TITLES[code] ?: code} ×$k", style = MaterialTheme.typography.bodySmall) }
                }
                HorizontalDivider()
                ModeRow(mode == ImportMode.MERGE, "Merge (recommended)", "Adds new rows. For existing rows the newer Edited At wins; read progress is only ever raised, never lowered.") { mode = ImportMode.MERGE }
                ModeRow(mode == ImportMode.REPLACE, "Replace all", "Your library becomes exactly this file. A backup (novels.pre-replace.csv) is saved first.") { mode = ImportMode.REPLACE }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(mode) }) { Text(if (mode == ImportMode.REPLACE) "Replace" else "Merge", color = if (mode == ImportMode.REPLACE) Pal.Red else Color.Unspecified) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun ModeRow(selected: Boolean, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick), verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(top = 12.dp)) {
            Text(title)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ chapter-count review
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PendingCard(n: Novel, vm: NovelViewModel, onOpen: (() -> Unit)?) {
    val counts = n.siteCountMap()
    val conflict = n.pendingReason.startsWith("Sources disagree")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Chapter count needs review", style = MaterialTheme.typography.titleSmall, color = Pal.Yellow)
            if (onOpen != null) Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable(onClick = onOpen), contentAlignment = Alignment.CenterStart) { Text(n.name, style = MaterialTheme.typography.titleMedium) }
            Text("You have ${fmt(n.currentCount)}. ${n.pendingReason}.", style = MaterialTheme.typography.bodyMedium)
            if (counts.isNotEmpty()) Text(
                "Last scrape: " + counts.entries.joinToString("  ") { "${sourceLabel(it.key)} ${fmt(it.value)}" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!conflict) Button(onClick = { vm.acceptCount(n.uid) }) { Text("Accept ${fmt(n.pendingCount)}") }
                if (conflict) counts.forEach { (src, v) ->
                    Button(onClick = { vm.acceptCount(n.uid, src) }) { Text("Use ${sourceLabel(src)} ${fmt(v)}") }
                }
                OutlinedButton(onClick = { vm.keepCount(n.uid) }) { Text("Keep ${fmt(n.currentCount)}") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(vm: NovelViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val all by vm.all.collectAsState()
    val pending = all.filter { it.pendingCount > 0 }.sortedBy { it.name.lowercase() }
    val increases = pending.count { it.pendingCount > it.currentCount && !it.pendingReason.startsWith("Sources disagree") }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Review counts (${pending.size})") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (pending.isEmpty()) "Nothing to review."
                        else "Nothing here has been changed yet. Accept the site's count, pick a source when they disagree, or keep yours.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (increases > 0) Button(onClick = { vm.acceptAllIncreases() }) { Text("Accept all $increases increases") }
                }
            }
            items(pending, key = { it.uid }) { n -> PendingCard(n, vm, onOpen = { onOpen(n.uid) }) }
        }
    }
}

// ------------------------------------------------------------------ refresh queue
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(vm: NovelViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onVerify: () -> Unit) {
    val q by vm.queue.collectAsState()
    val done = q.count(QStatus.DONE)
    val failed = q.count(QStatus.FAILED)
    val blocked = q.count(QStatus.BLOCKED)
    val pending = q.count(QStatus.PENDING)
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Refresh queue") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (q.items.isEmpty()) {
                Text("Nothing queued. Use ⋮ → Refresh all links… on the library screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            LinearProgressIndicator(progress = { (done + failed) / q.items.size.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text("$done done · $failed failed · $blocked waiting for bot check · $pending pending (${q.items.size} total)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (q.running) OutlinedButton(onClick = { vm.cancelQueue() }) { Text("Pause") }
                if (!q.running && (blocked > 0 || pending > 0)) Button(onClick = { vm.resumeQueue() }) { Text("Resume") }
                if (!q.running && failed > 0) OutlinedButton(onClick = { vm.retryFailed() }) { Text("Retry failed") }
                if (!q.running && pending == 0 && blocked == 0) TextButton(onClick = { vm.clearQueue() }) { Text("Clear") }
            }
            if (blocked > 0) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("The site asked for a bot check.", color = Pal.Yellow)
                        Text("Open the verification browser, solve it, tap Done, then press Resume.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onVerify) { Text("Verify in browser") }
                    }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // problems first, then what is running/pending, then finished
                val order = listOf(QStatus.BLOCKED, QStatus.FAILED, QStatus.RUNNING, QStatus.PENDING, QStatus.DONE)
                items(q.items.sortedBy { order.indexOf(it.status) }, key = { it.uid }) { it ->
                    val color = when (it.status) {
                        QStatus.DONE -> Pal.Green
                        QStatus.FAILED -> Pal.Red
                        QStatus.BLOCKED -> Pal.Yellow
                        QStatus.RUNNING -> Pal.Blue
                        QStatus.PENDING -> Color.Gray
                    }
                    Row(Modifier.fillMaxWidth().clickable { onOpen(it.uid) }.defaultMinSize(minHeight = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Tag(it.status.name.lowercase(), color)
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(it.name, maxLines = 1)
                            if (it.note.isNotBlank()) Text(it.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (it.status == QStatus.FAILED && !q.running) TextButton(onClick = { vm.retryOne(it.uid) }) { Text("Retry") }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ data check
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecksScreen(vm: NovelViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val all by vm.all.collectAsState()
    val rows = remember(all) { all.flatMap { n -> n.issues().map { n to it } } }
    val grouped = rows.groupBy { it.second.code }
    val codes = ISSUE_TITLES.keys.filter { it in grouped }
    val pending = all.count { it.pendingCount > 0 }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Data check") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text(
                    "${rows.count { it.second.severity == Severity.WARN }} warnings, ${rows.count { it.second.severity == Severity.INFO }} notes" +
                            (if (pending > 0) "; $pending chapter counts are waiting in Review counts" else "") +
                            ". Dismissing a check hides it for that novel only.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            codes.forEach { code ->
                val list = grouped.getValue(code)
                item(key = "h$code") {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${ISSUE_TITLES[code]} (${list.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { list.forEach { (n, i) -> vm.dismissIssue(n.uid, i.code) } }) { Text("Dismiss all") }
                    }
                }
                items(list, key = { "${it.first.uid}:${it.second.code}" }) { (n, issue) ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(n.uid) }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = if (issue.severity == Severity.WARN) Pal.Red else Pal.Yellow)
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(n.name, maxLines = 1)
                            Text(issue.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { vm.dismissIssue(n.uid, issue.code) }) { Text("Dismiss") }
                    }
                }
            }
            if (codes.isEmpty()) item { Text("No data problems found.", modifier = Modifier.padding(top = 16.dp)) }
        }
    }
}