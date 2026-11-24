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
    /** Lower-case genre names; a novel matches if it has any of them. */
    val genres: Set<String> = emptySet(),
    val download: Tri = Tri.ALL,
    val review: Tri = Tri.ALL,
    val converted: Tri = Tri.ALL,
    val sent: Tri = Tri.ALL,
    /** Read chapters have reached the latest chapter count (whether the novel is running or completed). */
    val caughtUp: Tri = Tri.ALL,
    /** Quick toggle: downloaded chapters not yet read. */
    val ready: Boolean = false,
) {
    val isDefault: Boolean get() = this == FilterState()
    val activeCount: Int get() = listOf(
        reading.isNotEmpty(), novel.isNotEmpty(), likely.isNotEmpty(), genres.isNotEmpty(), download != Tri.ALL, review != Tri.ALL,
        converted != Tri.ALL, sent != Tri.ALL, caughtUp != Tri.ALL, ready,
    ).count { it }

    fun test(n: Novel): Boolean =
        (reading.isEmpty() || n.readingStatus in reading) &&
            (novel.isEmpty() || n.novelStatus in novel) &&
            (likely.isEmpty() || n.likely in likely) &&
            (genres.isEmpty() || n.genreSet().any { it in genres }) &&
            download.matches(n.needsDownload) && review.matches(n.needsReview) &&
            converted.matches(n.conversion) && sent.matches(n.sent) && caughtUp.matches(n.isCaughtUp) &&
            (!ready || n.isReadyToRead)
}

enum class SortBy(val label: String) {
    NAME("Name"), RECENT("Recently edited"), ADDED("Recently added"), READY("Ready to read"),
    UNREAD("Unread"), PROGRESS("Progress"), LIKELY("Most likely"),
    LAST_READ("Last read"), STALE("Stalest first"), REFRESH("Least recently refreshed"),
}

/** A short-lived "Undo" offered in the snackbar after a read / downloaded edit. */
data class UndoOffer(val id: Long, val text: String)

/** Fields the library's multi-select "Edit" can set on several novels at once. */
enum class BulkField(val label: String, val options: List<String>) {
    LIKELY_TO_READ("Likely to read", LIKELY),
    READING_STATUS("Reading status", READING),
    NOVEL_STATUS_FIELD("Novel status", NOVEL_STATUS),
    CONVERSION("Conversion", listOf("Yes", "No")),
    SENT("Sent", listOf("Yes", "No")),
}

/** What the library search looks at. Titles = name and alternative names. */
enum class SearchScope(val label: String) { TITLES("Titles"), ALL("All text") }

/** A novel whose genres overlap with another's; [percent] = shared genres / all genres of both. */
data class SimilarNovel(val novel: Novel, val percent: Int, val shared: List<String>)

/** Result of "Add several": [report] starts with a one-line summary, then one line per problem. */
data class BulkAddState(val finished: Boolean = false, val report: List<String> = emptyList())

enum class ImportMode { MERGE, REPLACE }

data class ImportPlan(
    val result: CsvResult,
    val newRows: Int, val matched: Int, val matchedByName: Int, val csvNewer: Int, val keptYours: Int,
    /** Data-check codes the imported rows would trigger, with counts. */
    val warnings: Map<String, Int>,
)

enum class QStatus { PENDING, RUNNING, DONE, FAILED, BLOCKED }
data class QItem(
    val uid: String, val name: String, val status: QStatus = QStatus.PENDING, val note: String = "",
    /** Set for novels added from a link: after the first successful fetch the title made from the page name is replaced by the site's title. */
    val renameOnSuccess: Boolean = false,
)
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
    // linked novels first (never refreshed = oldest), novels without a site link last
    SortBy.REFRESH -> compareBy<Novel> { if (it.slug.isBlank()) 1 else 0 }.thenBy { it.lastRefreshAt }.thenBy { it.name.lowercase() }
}

class NovelViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val store = NovelStore.get(ctx)
    private val scraper = Scraper(ctx)

    val all: StateFlow<List<Novel>> = store.novels
    /** Non-null while the last save failed (changes exist only in memory until a save succeeds). */
    val saveError: StateFlow<String?> = store.saveError

    /** Reading queue: novels you lined up to read next, in the order you chose (Queue Position 1..n; 0 = not queued). */
    /** Every genre in the library (lower-case) with how many novels have it, most common first. Feeds the genre filter. */
    val genreOptions: StateFlow<List<Pair<String, Int>>> = all
        .map { l ->
            l.flatMap { it.genreSet() }.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key to it.value }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The closest novels by genre: shared genres divided by all genres of both, best first. Needs the novel to have genres. */
    fun similarByGenre(uid: String, limit: Int = 5): List<SimilarNovel> {
        val mine = novel(uid)?.genreSet().orEmpty()
        if (mine.isEmpty()) return emptyList()
        return all.value.asSequence().filter { it.uid != uid }.mapNotNull { o ->
            val theirs = o.genreSet()
            val shared = mine intersect theirs
            if (shared.isEmpty()) null
            else SimilarNovel(o, 100 * shared.size / (mine union theirs).size, shared.sorted().map { titleCase(it) })
        }.sortedWith(compareByDescending<SimilarNovel> { it.percent }.thenBy { it.novel.key }.thenBy { it.novel.uid })
            .take(limit).toList()
    }

    private val queueOrder: Comparator<Novel> = compareBy<Novel> { it.queuePos }.thenBy { it.key }.thenBy { it.uid }
    /** Current queue straight from the store (not the delayed StateFlow), so back-to-back edits can't race. */
    private fun queueNow() = all.value.filter { it.queuePos > 0 }.sortedWith(queueOrder)

    val readingQueue: StateFlow<List<Novel>> = all
        .map { l -> l.filter { it.queuePos > 0 }.sortedWith(queueOrder) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** First queued novel (in your order) with chapters ready, or null with a short explanation. Never changes anything. */
    fun nextToRead(): Novel? {
        val q = queueNow()
        if (q.isEmpty()) { say("Your reading queue is empty. Open a novel and tap Queue."); return null }
        val n = q.firstOrNull { it.isNextEligible }
        if (n == null) say("Nothing in your queue has chapters ready to read.")
        return n
    }

    fun addToReadingQueue(uid: String) {
        val n = novel(uid) ?: return
        if (n.queuePos > 0) return
        val next = (all.value.maxOfOrNull { it.queuePos } ?: 0) + 1
        store.edit(uid) { it.copy(queuePos = next) }
        say("Added to reading queue (#$next)")
    }

    /** Removes a novel from the queue and renumbers the rest so positions stay 1..n. Opening or queueing never touches progress or status. */
    fun removeFromReadingQueue(uid: String) {
        if (novel(uid)?.queuePos?.let { it > 0 } != true) return
        val order = queueNow().map { it.uid }.filter { it != uid }
        store.transform { x ->
            when {
                x.uid == uid -> x.copy(queuePos = 0)
                x.queuePos > 0 -> order.indexOf(x.uid).let { i -> if (i + 1 == x.queuePos) x else x.copy(queuePos = i + 1) }
                else -> x
            }
        }
    }

    fun moveInReadingQueue(uid: String, delta: Int) {
        val order = queueNow().map { it.uid }.toMutableList()
        val i = order.indexOf(uid)
        val j = i + delta
        if (i < 0 || j !in order.indices) return
        val other = order[j]; order[j] = order[i]; order[i] = other
        store.transform { x ->
            if (x.queuePos > 0) order.indexOf(x.uid).let { k -> if (k + 1 == x.queuePos) x else x.copy(queuePos = k + 1) } else x
        }
    }
    val query = MutableStateFlow("")
    val filters = MutableStateFlow(FilterState())
    // The chosen sort is remembered between launches (plain SharedPreferences; the library's filters are not saved).
    private val prefs = ctx.getSharedPreferences("novel_tracker_prefs", android.content.Context.MODE_PRIVATE)
    val sort = MutableStateFlow(
        runCatching { SortBy.valueOf(prefs.getString("sort", null) ?: "NAME") }.getOrDefault(SortBy.NAME),
    )
    /** Search looks at titles only by default; the choice is remembered. */
    val searchScope = MutableStateFlow(
        runCatching { SearchScope.valueOf(prefs.getString("search_scope", null) ?: "TITLES") }.getOrDefault(SearchScope.TITLES),
    )
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    // One-step undo for read-count and downloaded-through edits. Only those progress fields are restored, so a
    // refresh or any other edit made in the meantime is not rolled back. Snapshots are kept by id so an older
    // snackbar can never undo a newer edit.
    val undoOffers = MutableSharedFlow<UndoOffer>(extraBufferCapacity = 8)
    private val undoSnaps = LinkedHashMap<Long, Novel>()
    private var undoSeq = 0L

    private fun offerUndo(before: Novel, text: String) {
        val id = ++undoSeq
        undoSnaps[id] = before
        while (undoSnaps.size > 8) undoSnaps.remove(undoSnaps.keys.first())
        undoOffers.tryEmit(UndoOffer(id, text))
    }

    fun undo(id: Long) {
        val b = undoSnaps.remove(id) ?: return
        store.edit(b.uid) {
            it.copy(
                readCount = b.readCount, lastReadAt = b.lastReadAt, readingStatus = b.readingStatus,
                downloadedThrough = b.downloadedThrough, downloadedSource = b.downloadedSource,
            )
        }
        say("Undone")
    }
    val importPlan = MutableStateFlow<ImportPlan?>(null)
    val busy = MutableStateFlow<String?>(null)
    val queue: StateFlow<QueueState> = RefreshEngine.state
    private var queueJob: Job? = null

    val shown: StateFlow<List<Novel>> = combine(all, query, filters, sort, searchScope) { list, q, f, s, scope ->
        val tokens = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        list.filter { n ->
            f.test(n) && (tokens.isEmpty() || run {
                val hay = (if (scope == SearchScope.TITLES) "${n.name} ${n.altNames}"
                else "${n.name} ${n.altNames} ${n.author} ${n.genres} ${n.remarks} ${n.description}").lowercase()
                tokens.all { it in hay }
            })
        }.sortedWith(s.comparator().thenBy { it.key }.thenBy { it.uid }) // deterministic: ties fall back to normalised name, then Stable ID
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Novels with at least one un-dismissed WARN data check (precomputed off the main thread). */
    val warnUids: StateFlow<Set<String>> = all.map { list ->
        list.filter { n -> n.issues().any { it.severity == Severity.WARN } }.map { it.uid }.toSet()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        RefreshEngine.ensureLoaded(ctx)
        if (!store.ensureLoaded()) seedFromAssets()
        viewModelScope.launch { sort.collect { prefs.edit().putString("sort", it.name).apply() } }
        viewModelScope.launch { searchScope.collect { prefs.edit().putString("search_scope", it.name).apply() } }
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

    /**
     * Sets one field to [value] on every selected novel in a single save. Novels that already have the value are left
     * untouched (their Edited At does not move). The caller confirms with the user first.
     */
    fun bulkSet(uids: Set<String>, field: BulkField, value: String) {
        val yes = value == "Yes"
        fun apply(n: Novel): Novel = when (field) {
            BulkField.LIKELY_TO_READ -> if (n.likely == value) n else n.copy(likely = value)
            BulkField.READING_STATUS -> if (n.readingStatus == value) n else n.copy(readingStatus = value)
            BulkField.NOVEL_STATUS_FIELD -> if (n.novelStatus == value) n else n.copy(novelStatus = value)
            BulkField.CONVERSION -> if (n.conversion == yes) n else n.copy(conversion = yes)
            BulkField.SENT -> if (n.sent == yes) n else n.copy(sent = yes)
        }
        val changed = all.value.count { it.uid in uids && apply(it) !== it }
        store.transform { n -> if (n.uid in uids) apply(n) else n }
        say("${field.label} set to $value on $changed novel${if (changed == 1) "" else "s"}")
    }

    fun setRead(uid: String, v: Int) {
        val before = novel(uid) ?: return
        val nv = v.coerceAtLeast(0)
        if (nv == before.readCount) return
        store.edit(uid) { it.copy(readCount = nv, lastReadAt = System.currentTimeMillis()).afterProgress() }
        offerUndo(before, "Read ${fmt(before.readCount)} \u2192 ${fmt(nv)}")
    }

    fun setDownloaded(uid: String, v: Int) {
        val before = novel(uid) ?: return
        val nv = v.coerceAtLeast(0)
        store.edit(uid) { it.copy(downloadedThrough = nv, downloadedSource = "manual") }
        if (nv != before.downloadedThrough) offerUndo(before, "Downloaded ${fmt(before.downloadedThrough)} \u2192 ${fmt(nv)}")
    }

    /** Typing the count yourself also settles any pending proposal. */
    fun setCurrent(uid: String, v: Int) = store.edit(uid) { it.copy(currentCount = v.coerceAtLeast(0), pendingCount = 0, pendingReason = "").afterProgress() }

    /** Read := Downloaded Through Chapter. Never lowers progress. */
    fun markAllDownloadedRead(uid: String) {
        val n = novel(uid) ?: return
        if (n.downloadedThrough > n.readCount) {
            store.edit(uid) { it.copy(readCount = it.downloadedThrough, lastReadAt = System.currentTimeMillis()).afterProgress() }
            offerUndo(n, "Read count set to ${fmt(n.downloadedThrough)}")
        } else say("Nothing downloaded beyond chapter ${fmt(n.readCount)}")
    }

    fun dismissIssue(uid: String, code: String) = store.edit(uid) { it.copy(dismissed = (it.dismissedSet() + code).joinToString(";")) }

    fun add(name: String, link: String) {
        val nm = name.trim()
        if (nm.isEmpty()) return
        if (all.value.any { it.key == nameKey(nm) }) { say("'$nm' already exists"); return }
        val given = slugFrom(link)
        val guessed = given.isBlank()
        val slug = if (guessed) slugFromName(nm) else given
        // A pasted full link also says which mirror it is on, so only that one is tried.
        val n = Novel(name = nm, slug = slug, sites = siteFromLink(link))
        store.add(n)
        say(if (guessed && slug.isNotBlank()) "Added $nm (link guessed from the name: $slug)" else "Added $nm")
        if (n.slug.isNotBlank()) refreshOne(n.uid)
    }

    /** Why [name] can't be the new title of [uid] (empty, or another novel already has it), or null if it can. */
    fun renameProblem(uid: String, name: String): String? {
        val nm = name.trim()
        if (nm.isEmpty()) return "Enter a title"
        val clash = all.value.firstOrNull { it.uid != uid && it.key == nameKey(nm) }
        return clash?.let { "Already in the library as \"${it.name}\"" }
    }

    fun rename(uid: String, name: String) {
        val nm = name.trim()
        if (renameProblem(uid, nm) != null) return
        store.edit(uid) { it.copy(name = nm) }
        say("Renamed to $nm")
    }

    fun delete(uid: String) = store.remove(uid)

    // ---------------------------------------------------------------- add several at once
    val bulkAdd = MutableStateFlow(BulkAddState())

    /**
     * Adds a pasted list, one novel name or link per line, instantly. A link gets a title made from its page name (the
     * site's title replaces it after the first fetch); a name keeps the name you typed with a guessed page name. Lines
     * already in the library are skipped. The details (genres, author, chapters, cover...) are fetched one novel at a time
     * by the refresh queue in the background, so the app stays usable. Only started by a button.
     */
    fun startBulkAdd(text: String) {
        val lines = text.lines().map { it.trim().trimStart('-', '*', '\u2022').trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.distinct()
        if (lines.isEmpty()) return
        val problems = ArrayList<String>()
        val items = ArrayList<QItem>()
        var added = 0
        var skipped = 0
        for (line in lines) {
            val isLink = line.contains('/') || line.startsWith("http", true) || line.contains(".html", true)
            val slug = if (isLink) slugFrom(line) else slugFromName(line)
            val name = if (isLink) placeholderName(slug) else line
            if (name.isBlank()) { problems.add("Skipped (nothing usable): $line"); skipped++; continue }
            if (all.value.any { (isLink && slug.isNotBlank() && it.slug == slug) || it.key == nameKey(name) }) { skipped++; continue }
            val n = Novel(name = name, slug = slug, sites = if (isLink) siteFromLink(line) else "")
            store.add(n)
            added++
            if (slug.isNotBlank()) items.add(QItem(n.uid, n.name, renameOnSuccess = isLink))
            else problems.add("Added without a link (no page name could be made): $line")
        }
        if (items.isNotEmpty()) {
            RefreshEngine.append(ctx, items)
            // If a run is already going it picks the new items up by itself; otherwise start one in the background.
            if (!queue.value.running) BackgroundRefresh.schedule(ctx)
        }
        val summary = "Added $added, skipped $skipped (already in the library)"
        val tail = if (items.isNotEmpty()) listOf(
            "${items.size} novel${if (items.size == 1) " is" else "s are"} being filled in from the sites in the background (title for links, genres, author, chapters, cover). Progress is in the refresh strip; one that fails shows there as failed.",
        ) else emptyList()
        bulkAdd.value = BulkAddState(finished = true, report = listOf(summary) + tail + problems)
    }

    fun clearBulkAdd() { bulkAdd.value = BulkAddState() }

    // ---------------------------------------------------------------- download counts from the Windows app
    /** Reads text copied from the Windows app and works out what would change. Changes nothing. */
    fun planDownloadSync(text: String): DownloadSyncPlan = DownloadSync.plan(text, all.value)

    /** Sets the downloaded-through chapter of every listed novel in one save. */
    fun applyDownloadSync(updates: List<DownloadUpdate>) {
        if (updates.isEmpty()) return

        val byUid = updates.associateBy { it.uid }

        store.transform { novel ->
            val update = byUid[novel.uid] ?: return@transform novel

            val downloadChanged = novel.downloadedThrough != update.to
            val conversionChanged = !novel.conversion
            val sentChanged = !novel.sent

            if (!downloadChanged) {
                return@transform novel
            }

            novel.copy(
                downloadedThrough = update.to,
                downloadedSource = "windows",
                conversion = true,
                sent = true
            )
        }

        say("Updated downloads for ${updates.size} novel${if (updates.size == 1) "" else "s"}")
    }

    fun retrySave() = store.retrySave()

    /** Non-null if novels.csv could not be read at startup (the backup was used instead). Shown once by the app shell. */
    fun startupNotice(): String? = store.loadNotice

    fun clearFilters() { filters.value = FilterState() }

    // ---------------------------------------------------------------- chapter-count review
    fun acceptCount(uid: String, source: String? = null) {
        val n = novel(uid) ?: return
        val v = if (source != null) n.siteCountMap()[source] ?: return else n.pendingCount
        if (v <= 0) return
        // Choosing a site (Use .com / Use .net) is remembered as the preferred source, so later refreshes use it
        // automatically instead of asking again whenever the sites disagree.
        store.edit(uid) {
            it.copy(currentCount = v, pendingCount = 0, pendingReason = "", preferredSource = source ?: it.preferredSource)
                .afterProgress().withoutKept()
        }
    }

    /** Keep my count. Remembers the site numbers it was decided for, so the same numbers aren't proposed again. */
    fun keepCount(uid: String) = store.edit(uid) {
        it.copy(pendingCount = 0, pendingReason = "").withKept(countsSignature(it.siteCountMap()))
    }

    private fun isIncrease(n: Novel) = n.pendingCount > n.currentCount && !n.pendingReason.startsWith("Sources disagree")

    fun acceptAllIncreases() {
        val k = all.value.count { isIncrease(it) }
        if (k == 0) return
        store.transform { if (isIncrease(it)) it.copy(currentCount = it.pendingCount, pendingCount = 0, pendingReason = "").afterProgress().withoutKept() else it }
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
    private fun outcome(n: Novel) = refreshOutcome(n)

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

    private fun requeue(vararg from: QStatus) = RefreshEngine.requeue(ctx, *from)

    /** Builds the queue from the library. Returns false (with a message) if there is nothing to refresh. */
    private fun buildRefreshAll(skipToday: Boolean): Boolean {
        if (queue.value.running) return false
        val dayStart = startOfToday()
        val eligible = all.value.filter { it.slug.isNotBlank() && !(skipToday && it.lastRefreshAt >= dayStart) }
        if (eligible.isEmpty()) { say("Nothing to refresh"); return false }
        RefreshEngine.replace(ctx, eligible.map { QItem(it.uid, it.name) })
        return true
    }

    /** Builds the queue and starts it in the app. Only ever called from a button. */
    fun startRefreshAll(skipToday: Boolean) { if (buildRefreshAll(skipToday)) runQueue() }

    /**
     * Builds the queue and hands it to WorkManager so it keeps going when the app is closed (needs a connection and a
     * battery that is not low). Only ever called from a button.
     */
    fun startRefreshAllInBackground(skipToday: Boolean) {
        if (buildRefreshAll(skipToday)) {
            BackgroundRefresh.schedule(ctx)
            say("Refreshing in the background. Progress is in Refresh queue.")
        }
    }

    /** How many novels a refresh works on at once (1 to 10). Saved; applies from the next run. */
    val refreshParallel = MutableStateFlow(RefreshSettings.parallel(ctx))
    fun setRefreshParallel(n: Int) {
        val v = n.coerceIn(RefreshSettings.MIN, RefreshSettings.MAX)
        refreshParallel.value = v
        RefreshSettings.setParallel(ctx, v)
    }

    fun resumeQueue() { requeue(QStatus.BLOCKED, QStatus.RUNNING); runQueue() }
    fun retryFailed() { requeue(QStatus.FAILED); runQueue() }
    fun retryOne(uid: String) { RefreshEngine.setItem(ctx, uid, QStatus.PENDING); runQueue() }
    fun cancelQueue() { queueJob?.cancel(); BackgroundRefresh.cancel(ctx) }
    fun clearQueue() { RefreshEngine.clear(ctx) }

    private fun runQueue() {
        if (queueJob?.isActive == true) return
        queueJob = viewModelScope.launch { RefreshEngine.run(ctx, store, scraper) { say(it) } }
    }
}