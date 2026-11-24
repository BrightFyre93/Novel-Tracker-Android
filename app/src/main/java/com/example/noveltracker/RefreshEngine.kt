package com.example.noveltracker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** Short summary of what a refresh left for you to look at (queue rows, snackbars). */
fun refreshOutcome(n: Novel): String {
    val parts = ArrayList<String>()
    if (n.pendingCount > 0) parts.add("chapter count needs review")
    val warn = n.issues().count { it.severity == Severity.WARN }
    if (warn > 0) parts.add("$warn data warning" + if (warn > 1) "s" else "")
    return parts.joinToString(", ")
}

/** How many novels a refresh works on at the same time (1 to 10). Applies from the next run. Saved in SharedPreferences. */
object RefreshSettings {
    const val MIN = 1
    const val MAX = 10
    private const val KEY = "refresh_parallel"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("novel_tracker_prefs", Context.MODE_PRIVATE)

    fun parallel(ctx: Context): Int = prefs(ctx).getInt(KEY, MIN).coerceIn(MIN, MAX)

    fun setParallel(ctx: Context, n: Int) { prefs(ctx).edit().putInt(KEY, n.coerceIn(MIN, MAX)).apply() }
}

/**
 * The refresh queue shared by the in-app Refresh queue screen and the background worker. There is one instance per
 * process, and the queue is saved to refresh_queue.json after every change, so it survives the app being closed or
 * killed. Only one runner works through it at a time. Refreshing is still always started by a button.
 */
object RefreshEngine {
    val state = MutableStateFlow(QueueState())
    private val runner = Mutex()
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "refresh_queue.json")

    /** Loads the saved queue once per process. An item that was RUNNING when the process died goes back to PENDING. */
    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = file(ctx)
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            val items = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val st = runCatching { QStatus.valueOf(o.getString("s")) }.getOrDefault(QStatus.PENDING)
                QItem(
                    o.getString("u"), o.optString("n"), if (st == QStatus.RUNNING) QStatus.PENDING else st, o.optString("t"),
                    renameOnSuccess = o.optBoolean("r", false),
                )
            }
            state.value = QueueState(items = items, running = false)
        }
    }

    @Synchronized
    private fun save(ctx: Context) {
        runCatching {
            val arr = JSONArray()
            state.value.items.forEach {
                arr.put(JSONObject().put("u", it.uid).put("n", it.name).put("s", it.status.name).put("t", it.note).put("r", it.renameOnSuccess))
            }
            val f = file(ctx)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    private fun mutate(ctx: Context, f: (QueueState) -> QueueState) { state.update(f); save(ctx) }

    fun replace(ctx: Context, items: List<QItem>) = mutate(ctx) { QueueState(items = items) }

    /** Adds items to the end of the queue (an older entry for the same novel is replaced). A runner that is going picks them up. */
    fun append(ctx: Context, items: List<QItem>) = mutate(ctx) { q ->
        q.copy(items = q.items.filter { old -> items.none { it.uid == old.uid } } + items)
    }

    fun clear(ctx: Context) { if (!state.value.running) mutate(ctx) { QueueState() } }

    fun setItem(ctx: Context, uid: String, status: QStatus, note: String = "") =
        mutate(ctx) { q -> q.copy(items = q.items.map { if (it.uid == uid) it.copy(status = status, note = note) else it }) }

    fun requeue(ctx: Context, vararg from: QStatus) =
        mutate(ctx) { q -> q.copy(items = q.items.map { if (it.status in from) it.copy(status = QStatus.PENDING, note = "") else it }) }

    /** Takes the first PENDING item and marks it RUNNING in one atomic step, so two workers never take the same one. */
    private fun claimNext(ctx: Context): QItem? {
        var claimed: QItem? = null
        state.update { q ->
            val i = q.items.indexOfFirst { it.status == QStatus.PENDING }
            if (i < 0) { claimed = null; q }
            else {
                claimed = q.items[i]
                q.copy(items = q.items.mapIndexed { j, item -> if (j == i) item.copy(status = QStatus.RUNNING, note = "") else item })
            }
        }
        if (claimed != null) save(ctx)
        return claimed
    }

    /**
     * Works through the PENDING items, up to [RefreshSettings.parallel] novels at a time (the "Novels at a time" setting,
     * 1 to 10), saving every result to the library and every status change to disk. Each worker pauses a little between
     * novels. A bot check stops all workers from taking new items (that item becomes BLOCKED, the rest stay PENDING; pages
     * already being fetched finish). Safe to cancel: items that were RUNNING go back to PENDING. If another runner is
     * already active this returns at once.
     */
    suspend fun run(ctx: Context, store: NovelStore, scraper: Scraper, say: (String) -> Unit) {
        if (!runner.tryLock()) return
        try {
            mutate(ctx) { it.copy(running = true) }
            try {
                val stop = AtomicBoolean(false)
                coroutineScope {
                    repeat(RefreshSettings.parallel(ctx)) { w ->
                        launch {
                            if (w > 0) delay(w * 400L) // don't start every page at the same instant
                            while (!stop.get()) {
                                val next = claimNext(ctx) ?: break
                                val n = store.novels.value.firstOrNull { it.uid == next.uid }
                                if (n == null) { setItem(ctx, next.uid, QStatus.FAILED, "Removed from library"); continue }
                                val r = try {
                                    withContext(Dispatchers.Default) { scraper.fetch(n) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    setItem(ctx, next.uid, QStatus.FAILED, "${FailKind.NETWORK.label} \u2014 ${e.message ?: e.javaClass.simpleName}")
                                    continue
                                }
                                store.editScraped(n.uid) { x ->
                                    val applied = x.applyScrape(r)
                                    // A novel added from a link starts with a title made from its page name; take the site's real title
                                    // once, and only if you haven't renamed it since and no other novel already has that title.
                                    val title = r.siteTitle
                                    if (next.renameOnSuccess && r.ok && title.isNotBlank() && title != x.name && x.name == placeholderName(x.slug) &&
                                        store.novels.value.none { it.uid != x.uid && it.key == nameKey(title) }
                                    ) applied.copy(name = title) else applied
                                }
                                if (r.blocked) {
                                    setItem(ctx, next.uid, QStatus.BLOCKED, r.note)
                                    if (stop.compareAndSet(false, true)) say("Bot check. Open Verify in browser, solve it, then Resume.")
                                    break
                                }
                                if (r.ok) setItem(ctx, next.uid, QStatus.DONE, store.novels.value.firstOrNull { it.uid == n.uid }?.let { refreshOutcome(it) }.orEmpty())
                                else setItem(ctx, next.uid, QStatus.FAILED, r.note)
                                // pause between pages, not after the last one
                                if (!stop.get() && state.value.items.any { it.status == QStatus.PENDING }) delay(1000L + Random.nextLong(1500))
                            }
                        }
                    }
                }
                val q = state.value
                if (q.count(QStatus.PENDING) == 0 && q.count(QStatus.BLOCKED) == 0) {
                    say("Refresh finished: ${q.count(QStatus.DONE)} updated, ${q.count(QStatus.FAILED)} failed")
                    // Everything went through: there is nothing left to act on, so the list clears itself. If anything
                    // failed it stays, so you can see which ones and retry them.
                    if (q.count(QStatus.FAILED) == 0) mutate(ctx) { it.copy(items = emptyList()) }
                }
            } finally {
                mutate(ctx) { q ->
                    q.copy(running = false, items = q.items.map { if (it.status == QStatus.RUNNING) it.copy(status = QStatus.PENDING) else it })
                }
            }
        } finally {
            runner.unlock()
        }
    }
}

/** Runs the saved refresh queue while the app is closed. Started only from the "Keep going in the background" option. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        RefreshEngine.ensureLoaded(app)
        val store = NovelStore.get(app)
        store.ensureLoaded()
        // A bot check just stops the run (the item is marked BLOCKED); you solve it in the app and press Resume.
        RefreshEngine.run(app, store, Scraper(app)) { }
        return Result.success()
    }
}

object BackgroundRefresh {
    private const val WORK_NAME = "refresh_queue"

    /** Needs a network connection and a battery that is not low. If a run is already waiting or going, that one is kept. */
    fun schedule(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME) }
}
