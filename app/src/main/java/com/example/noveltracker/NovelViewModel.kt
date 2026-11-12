package com.example.noveltracker

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class Tri(val label: String) { ALL("All"), YES("Yes"), NO("No") }

private fun Tri.matches(v: Boolean) = when (this) { Tri.ALL -> true; Tri.YES -> v; Tri.NO -> !v }

fun Set<String>.toggled(v: String): Set<String> = if (v in this) this - v else this + v

/**
 * Grouped, stackable filters. Groups combine with AND; several values inside one group combine with OR;
 * an empty set / ALL means "no restriction". Derived groups use the shared predicates in Data.kt.
 */
data class FilterState(
    val reading: Set<String> = emptySet(),
    val novel: Set<String> = emptySet(),
    val likely: Set<String> = emptySet(),
    val download: Tri = Tri.ALL,
    val review: Tri = Tri.ALL,
    /** Quick toggle: downloaded chapters not yet read. */
    val ready: Boolean = false,
) {
    val isDefault: Boolean get() = this == FilterState()
    val activeCount: Int get() = listOf(
        reading.isNotEmpty(), novel.isNotEmpty(), likely.isNotEmpty(), download != Tri.ALL, review != Tri.ALL, ready,
    ).count { it }

    fun test(n: Novel): Boolean =
        (reading.isEmpty() || n.readingStatus in reading) &&
            (novel.isEmpty() || n.novelStatus in novel) &&
            (likely.isEmpty() || n.likely in likely) &&
            download.matches(n.needsDownload) && review.matches(n.needsReview) &&
            (!ready || n.isReadyToRead)
}

enum class SortBy(val label: String) {
    NAME("Name"), RECENT("Recently edited"), ADDED("Recently added"), READY("Ready to read"),
    UNREAD("Unread"), PROGRESS("Progress"), LIKELY("Most likely"),
    LAST_READ("Last read"), STALE("Stalest first"),
}

enum class ImportMode { MERGE, REPLACE }

data class ImportPlan(
    val result: CsvResult,
    val newRows: Int, val matched: Int, val matchedByName: Int, val csvNewer: Int, val keptYours: Int,
    /** Data-check codes the imported rows would trigger, with counts. */
    val warnings: Map<String, Int>,
)

enum class QStatus { PENDING, RUNNING, DONE, FAILED, BLOCKED }
data class QItem(val uid: String, val name: String, val status: QStatus = QStatus.PENDING, val note: String = "")
data class QueueState(val items: List<QItem> = emptyList(), val running: Boolean = false) {
    fun count(s: QStatus) = items.count { it.status == s }
}

private fun SortBy.comparator(): Comparator<Novel> = when (this) {
    SortBy.NAME -> compareBy<Novel> { it.name.lowercase() }
    SortBy.RECENT -> compareByDescending<Novel> { it.editedAt }
    SortBy.ADDED -> compareByDescending<Novel> { it.createdAt }
    SortBy.READY -> compareByDescending<Novel> { it.readyToRead }.thenBy { it.name.lowercase() }
    SortBy.UNREAD -> compareByDescending<Novel> { it.unread }.thenBy { it.name.lowercase() }
    SortBy.PROGRESS -> compareByDescending<Novel> {
        if (it.currentCount == 0) 0.0 else it.readCount.toDouble() / it.currentCount
    }.thenBy { it.name.lowercase() }
    SortBy.LIKELY -> compareBy<Novel> { LIKELY.indexOf(it.likely) }
        .thenByDescending { it.readyToRead }.thenBy { it.name.lowercase() }
    // novels with no recorded read time go last in both orders
    SortBy.LAST_READ -> compareByDescending<Novel> { it.lastReadAt }.thenBy { it.name.lowercase() }
    SortBy.STALE -> compareBy<Novel> { if (it.lastReadAt == 0L) Long.MAX_VALUE else it.lastReadAt }.thenBy { it.name.lowercase() }
}

class NovelViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val store = NovelStore(ctx, viewModelScope)
    private val scraper = Scraper(ctx)

    val all: StateFlow<List<Novel>> = store.novels
    /** Non-null while the last save failed (changes exist only in memory until a save succeeds). */
    val saveError: StateFlow<String?> = store.saveError
    val query = MutableStateFlow("")
    val filters = MutableStateFlow(FilterState())
    val sort = MutableStateFlow(SortBy.NAME)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val importPlan = MutableStateFlow<ImportPlan?>(null)
    val busy = MutableStateFlow<String?>(null)
    val queue = MutableStateFlow(QueueState())
    private var queueJob: Job? = null

    val shown: StateFlow<List<Novel>> = combine(all, query, filters, sort) { list, q, f, s ->
        val tokens = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        list.filter { n ->
            f.test(n) && (tokens.isEmpty() || run {
                val hay = "${n.name} ${n.altNames} ${n.author} ${n.genres} ${n.remarks} ${n.description}".lowercase()
                tokens.all { it in hay }
            })
        }.sortedWith(s.comparator().thenBy { it.key }.thenBy { it.uid }) // deterministic: ties fall back to normalised name, then Stable ID
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Novels with at least one un-dismissed WARN data check (precomputed off the main thread). */
    val warnUids: StateFlow<Set<String>> = all.map { list ->
        list.filter { n -> n.issues().any { it.severity == Severity.WARN } }.map { it.uid }.toSet()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        if (!store.load()) seedFromAssets()
    }

    private fun say(s: String) { messages.tryEmit(s) }

    /** Optional: only if a seed.csv was bundled in assets (not needed; import from the More menu instead). */
    private fun seedFromAssets() {
        val has = ctx.assets.list("")?.contains("seed.csv") == true
        if (!has) return
        val r = Csv.toNovels(Csv.parse(Csv.decode(ctx.assets.open("seed.csv").use { it.readBytes() })))
        store.set(r.novels)
        say("Imported ${r.novels.size} novels from seed.csv")
    }

    fun novel(uid: String): Novel? = all.value.firstOrNull { it.uid == uid }

    // ---------------------------------------------------------------- progress & edits
    fun update(uid: String, f: (Novel) -> Novel) = store.edit(uid, f)

    fun setRead(uid: String, v: Int) = store.edit(uid) {
        val nv = v.coerceAtLeast(0)
        if (nv == it.readCount) it else it.copy(readCount = nv, lastReadAt = System.currentTimeMillis()).afterProgress()
    }

    fun setDownloaded(uid: String, v: Int) = store.edit(uid) { it.copy(downloadedThrough = v.coerceAtLeast(0), downloadedSource = "manual") }

    /** Typing the count yourself also settles any pending proposal. */
    fun setCurrent(uid: String, v: Int) = store.edit(uid) { it.copy(currentCount = v.coerceAtLeast(0), pendingCount = 0, pendingReason = "").afterProgress() }

    /** Read := Downloaded Through Chapter. Never lowers progress. */
    fun markAllDownloadedRead(uid: String) {
        val n = novel(uid) ?: return
        if (n.downloadedThrough > n.readCount) {
            store.edit(uid) { it.copy(readCount = it.downloadedThrough, lastReadAt = System.currentTimeMillis()).afterProgress() }
            say("Read count set to ${fmt(n.downloadedThrough)}")
        } else say("Nothing downloaded beyond chapter ${fmt(n.readCount)}")
    }

    fun dismissIssue(uid: String, code: String) = store.edit(uid) { it.copy(dismissed = (it.dismissedSet() + code).joinToString(";")) }

    fun add(name: String, link: String) {
        val nm = name.trim()
        if (nm.isEmpty()) return
        if (all.value.any { it.key == nameKey(nm) }) { say("'$nm' already exists"); return }
        val n = Novel(name = nm, slug = slugFrom(link))
        store.add(n)
        say("Added $nm")
        if (n.slug.isNotBlank()) refreshOne(n.uid)
    }

    fun delete(uid: String) = store.remove(uid)

    fun retrySave() = store.retrySave()

    fun clearFilters() { filters.value = FilterState() }

    // ---------------------------------------------------------------- chapter-count review
    fun acceptCount(uid: String, source: String? = null) {
        val n = novel(uid) ?: return
        val v = if (source != null) n.siteCountMap()[source] ?: return else n.pendingCount
        if (v <= 0) return
        store.edit(uid) { it.copy(currentCount = v, pendingCount = 0, pendingReason = "").afterProgress() }
    }

    fun keepCount(uid: String) = store.edit(uid) { it.copy(pendingCount = 0, pendingReason = "") }

    private fun isIncrease(n: Novel) = n.pendingCount > n.currentCount && !n.pendingReason.startsWith("Sources disagree")

    fun acceptAllIncreases() {
        val k = all.value.count { isIncrease(it) }
        if (k == 0) return
        store.transform { if (isIncrease(it)) it.copy(currentCount = it.pendingCount, pendingCount = 0, pendingReason = "").afterProgress() else it }
        say("Accepted $k increases")
    }

    // ---------------------------------------------------------------- import / export
    fun readForImport(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Cannot open file")
                importPlan.value = plan(Csv.toNovels(Csv.parse(Csv.decode(bytes))))
            } catch (e: Exception) {
                say("Import failed: ${e.message}")
            }
        }
    }

    private fun plan(r: CsvResult): ImportPlan {
        val cur = all.value
        val byUid = cur.associateBy { it.uid }
        val byKey = cur.associateBy { it.key }
        var fresh = 0; var matched = 0; var byName = 0; var newer = 0; var kept = 0
        for (n in r.novels) {
            var ex = byUid[n.uid]
            if (ex == null) { ex = byKey[n.key]; if (ex != null) byName++ }
            if (ex == null) fresh++ else {
                matched++
                if (r.hasEditedAt && n.editedAt > ex.editedAt) newer++ else kept++
            }
        }
        val warn = r.novels.flatMap { it.issues() }.groupingBy { it.code }.eachCount()
        return ImportPlan(r, fresh, matched, byName, newer, kept, warn)
    }

    fun cancelImport() { importPlan.value = null }

    fun applyImport(mode: ImportMode) {
        val p = importPlan.value ?: return
        importPlan.value = null
        if (mode == ImportMode.REPLACE) {
            if (!store.backup("pre-replace")) {
                importPlan.value = p // keep the preview so nothing is lost; nothing was replaced
                say("Import cancelled: couldn't back up the current library first")
                return
            }
            store.set(p.result.novels)
            say("Replaced library with ${p.result.novels.size} novels (backup: novels.pre-replace.csv)")
            return
        }
        // MERGE: new rows are added. For a match the newer Edited At wins, and read progress is only ever raised.
        val out = all.value.toMutableList()
        val byUid = HashMap<String, Int>(); val byKey = HashMap<String, Int>()
        out.forEachIndexed { i, n -> byUid[n.uid] = i; byKey[n.key] = i }
        var added = 0; var updated = 0; var raised = 0
        val now = System.currentTimeMillis()
        for (n in p.result.novels) {
            val i = byUid[n.uid] ?: byKey[n.key]
            if (i == null) {
                out.add(n); byUid[n.uid] = out.size - 1; byKey[n.key] = out.size - 1; added++
                continue
            }
            val ex = out[i]
            val csvWins = p.result.hasEditedAt && n.editedAt > ex.editedAt
            val base = if (csvWins) n.copy(uid = ex.uid, createdAt = ex.createdAt) else ex
            val read = maxOf(ex.readCount, n.readCount)
            var merged = base.copy(readCount = read, lastReadAt = maxOf(ex.lastReadAt, n.lastReadAt))
            if (read != base.readCount) { merged = merged.afterProgress().copy(editedAt = now); raised++ }
            if (merged != ex) { out[i] = merged; updated++ }
        }
        store.set(out)
        say("Merged: $added added, $updated updated" + if (raised > 0) " ($raised read counts raised)" else "")
    }

    fun export(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                (ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Cannot open file"))
                    .use { it.write(Csv.export(all.value)) }
                say("Exported ${all.value.size} novels")
            } catch (e: Exception) {
                say("Export failed: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- scraping (always user-initiated)
    private fun outcome(n: Novel): String {
        val parts = ArrayList<String>()
        if (n.pendingCount > 0) parts.add("chapter count needs review")
        val warn = n.issues().count { it.severity == Severity.WARN }
        if (warn > 0) parts.add("$warn data warning" + if (warn > 1) "s" else "")
        return parts.joinToString(", ")
    }

    fun refreshOne(uid: String) {
        viewModelScope.launch {
            val n = novel(uid) ?: return@launch
            busy.value = uid
            val r = scraper.fetch(n)
            store.editScraped(uid) { it.applyScrape(r) }
            busy.value = null
            val after = novel(uid)
            say(
                if (r.ok) "Updated ${n.name}" + (after?.let { outcome(it) }?.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: "")
                else "Refresh failed: ${r.note}"
            )
        }
    }

    private fun setItem(uid: String, status: QStatus, note: String = "") {
        queue.update { q -> q.copy(items = q.items.map { if (it.uid == uid) it.copy(status = status, note = note) else it }) }
    }

    private fun requeue(vararg from: QStatus) {
        queue.update { q -> q.copy(items = q.items.map { if (it.status in from) it.copy(status = QStatus.PENDING, note = "") else it }) }
    }

    /** Builds the queue and starts it. Only ever called from a button. */
    fun startRefreshAll(skipToday: Boolean) {
        if (queue.value.running) return
        val dayStart = startOfToday()
        val eligible = all.value.filter { it.slug.isNotBlank() && !(skipToday && it.lastRefreshAt >= dayStart) }
        if (eligible.isEmpty()) { say("Nothing to refresh"); return }
        queue.value = QueueState(items = eligible.map { QItem(it.uid, it.name) })
        runQueue()
    }

    fun resumeQueue() { requeue(QStatus.BLOCKED, QStatus.RUNNING); runQueue() }
    fun retryFailed() { requeue(QStatus.FAILED); runQueue() }
    fun retryOne(uid: String) {
        queue.update { q -> q.copy(items = q.items.map { if (it.uid == uid) it.copy(status = QStatus.PENDING, note = "") else it }) }
        runQueue()
    }
    fun cancelQueue() { queueJob?.cancel() }
    fun clearQueue() { if (!queue.value.running) queue.value = QueueState() }

    private fun runQueue() {
        if (queueJob?.isActive == true) return
        queueJob = viewModelScope.launch {
            queue.update { it.copy(running = true) }
            try {
                while (true) {
                    val next = queue.value.items.firstOrNull { it.status == QStatus.PENDING } ?: break
                    val n = novel(next.uid)
                    if (n == null) { setItem(next.uid, QStatus.FAILED, "Removed from library"); continue }
                    setItem(next.uid, QStatus.RUNNING)
                    val r = scraper.fetch(n)
                    store.editScraped(n.uid) { it.applyScrape(r) }
                    if (r.blocked) {
                        setItem(next.uid, QStatus.BLOCKED, r.note)
                        say("Bot check. Open Verify in browser, solve it, then Resume.")
                        break
                    }
                    if (r.ok) setItem(next.uid, QStatus.DONE, novel(n.uid)?.let { outcome(it) }.orEmpty())
                    else setItem(next.uid, QStatus.FAILED, r.note)
                    delay(1000L + Random.nextLong(1500))
                }
                val q = queue.value
                if (q.count(QStatus.PENDING) == 0 && q.count(QStatus.BLOCKED) == 0) {
                    say("Refresh finished: ${q.count(QStatus.DONE)} updated, ${q.count(QStatus.FAILED)} failed")
                }
            } finally {
                queue.update { q ->
                    q.copy(running = false, items = q.items.map { if (it.status == QStatus.RUNNING) it.copy(status = QStatus.PENDING) else it })
                }
            }
        }
    }
}