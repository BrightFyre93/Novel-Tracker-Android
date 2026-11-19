package com.example.noveltracker

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * All novels live in memory and are saved to <app files>/novels.csv (same format as the export),
 * so there is no database to migrate and the file can later be shared with a Windows companion app.
 */
class NovelStore(private val ctx: Context, scope: CoroutineScope) {
    companion object {
        @Volatile private var instance: NovelStore? = null

        /**
         * The one store for the whole process. The screens and the background refresh worker both use it, so there is
         * only ever one writer of novels.csv (two separate copies would overwrite each other's changes).
         */
        fun get(ctx: Context): NovelStore = instance ?: synchronized(this) {
            instance ?: NovelStore(ctx.applicationContext, CoroutineScope(SupervisorJob() + Dispatchers.Default)).also { instance = it }
        }
    }

    private var loaded = false
    private var hadFile = false

    /** Loads novels.csv once per process. @return true if a saved library existed. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (!loaded) { hadFile = load(); loaded = true }
        return hadFile
    }

    private val file = File(ctx.filesDir, "novels.csv")
    val novels = MutableStateFlow<List<Novel>>(emptyList())

    /**
     * Non-null while the last save failed, i.e. the in-memory library is ahead of what is on disk.
     * Cleared by the next successful save. The next edit (or [retrySave]) writes the whole library again.
     */
    val saveError = MutableStateFlow<String?>(null)

    private val saveSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            for (u in saveSignal) {
                try {
                    writeAtomically(Csv.export(novels.value))
                    saveError.value = null
                } catch (e: Exception) {
                    saveError.value = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                }
            }
        }
    }

    /** Write to novels.csv.tmp, flush to disk, then move over novels.csv, so a failure keeps the previous file intact. */
    private fun writeAtomically(bytes: ByteArray) {
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Asks for another save of the current in-memory library (used by the Retry button). */
    fun retrySave() { saveSignal.trySend(Unit) }

    /** Set when the saved library could not be read at startup; the screens show it once. */
    var loadNotice: String? = null
        private set

    /**
     * Reads novels.csv. It only becomes the backup (novels.bak.csv) after it has been read successfully, so a damaged
     * file can no longer overwrite the good backup. If it can't be read, the backup is loaded instead and the bad file
     * is kept as novels.corrupt.csv.
     * @return true if a saved library existed.
     */
    fun load(): Boolean {
        if (!file.exists()) return false
        val bak = File(ctx.filesDir, "novels.bak.csv")
        val parsed = runCatching { Csv.toNovels(Csv.parse(Csv.decode(file.readBytes()))).novels }
        if (parsed.isSuccess) {
            runCatching { file.copyTo(bak, overwrite = true) }
            novels.value = parsed.getOrThrow()
            return true
        }
        runCatching { file.copyTo(File(ctx.filesDir, "novels.corrupt.csv"), overwrite = true) }
        val fromBackup = if (bak.exists()) runCatching { Csv.toNovels(Csv.parse(Csv.decode(bak.readBytes()))).novels }.getOrNull() else null
        novels.value = fromBackup ?: emptyList()
        loadNotice = if (fromBackup != null) "novels.csv could not be read, so the last backup was loaded (the unreadable file is kept as novels.corrupt.csv)"
        else "novels.csv could not be read and there is no usable backup (the unreadable file is kept as novels.corrupt.csv)"
        return true
    }

    /**
     * Writes the current in-memory library to novels.<tag>.csv (e.g. before a Replace import).
     * @return false if the backup could not be written, so callers can refuse to continue with a destructive step.
     */
    fun backup(tag: String): Boolean = try {
        File(ctx.filesDir, "novels.$tag.csv").writeBytes(Csv.export(novels.value))
        true
    } catch (e: Exception) {
        false
    }

    fun set(list: List<Novel>) { novels.value = list; saveSignal.trySend(Unit) }

    fun add(n: Novel) { novels.update { it + n }; saveSignal.trySend(Unit) }

    fun remove(uid: String) { novels.update { l -> l.filter { it.uid != uid } }; saveSignal.trySend(Unit) }

    fun edit(uid: String, f: (Novel) -> Novel) {
        val now = System.currentTimeMillis()
        novels.update { l -> l.map { if (it.uid == uid) f(it).copy(editedAt = now) else it } }
        saveSignal.trySend(Unit)
    }

    /**
     * For scrape results: like [edit], but Edited At only moves when something other than the refresh timestamps
     * changed. A refresh that finds nothing new is therefore not an "edit" (it won't reorder "Recently edited" or
     * win a Merge); the refresh timestamps are still saved.
     */
    fun editScraped(uid: String, f: (Novel) -> Novel) {
        val now = System.currentTimeMillis()
        novels.update { l ->
            l.map { n ->
                if (n.uid != uid) n else {
                    val r = f(n)
                    if (r.ignoringRefreshStamps() == n.ignoringRefreshStamps()) r.copy(editedAt = n.editedAt) else r.copy(editedAt = now)
                }
            }
        }
        saveSignal.trySend(Unit)
    }

    private fun Novel.ignoringRefreshStamps() = copy(lastRefreshAt = 0, lastAttemptAt = 0, lastChecked = "", editedAt = 0)

    /** Applies [f] to every novel; rows where it returns the same instance are left untouched (editedAt unchanged). */
    fun transform(f: (Novel) -> Novel) {
        val now = System.currentTimeMillis()
        novels.update { l -> l.map { val r = f(it); if (r === it) it else r.copy(editedAt = now) } }
        saveSignal.trySend(Unit)
    }
}
