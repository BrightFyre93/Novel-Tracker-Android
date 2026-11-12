package com.example.noveltracker

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

val READING = listOf("Not Started", "Running", "Completed", "Aborted")
val NOVEL_STATUS = listOf("Running", "Completed")
val LIKELY = listOf("Yes", "Maybe", "No")
val DL_SOURCES = listOf("rule", "sheet", "manual")

data class Novel(
    /** Stable ID: the identity used for matching across imports/devices. */
    val uid: String = UUID.randomUUID().toString(),
    val name: String,
    val readCount: Int = 0,
    /** Your accepted "latest chapter" count. Scrapes only change it automatically when safe (see [decideCount]). */
    val currentCount: Int = 0,
    /** Chapters 1..N are downloaded (assumed contiguous). */
    val downloadedThrough: Int = 0,
    /** rule = computed from Converted/Sent, sheet = recorded in your sheet, manual = typed in the app. */
    val downloadedSource: String = "rule",
    val likely: String = "Yes",
    val lastChecked: String = "",
    val readingStatus: String = "Not Started",
    val novelStatus: String = "Running",
    val conversion: Boolean = false,
    val sent: Boolean = false,
    val remarks: String = "",
    /** Chapter titles aren't "Chapter N": the scraper never touches the count. */
    val manualChapters: Boolean = false,
    /** "" = auto, otherwise a key of [BASES] (more sources can be added later). */
    val preferredSource: String = "",
    val slug: String = "",
    val sites: String = "",
    val author: String = "",
    val altNames: String = "",
    val genres: String = "",
    val source: String = "",
    val siteTitle: String = "",
    /** What the site says. Kept separate from [novelStatus], which is yours. */
    val siteStatus: String = "",
    /** Counts per source from the last successful scrape, e.g. "com=1841;net=1839". */
    val siteCounts: String = "",
    /** A proposed new count waiting for your review (0 = none). */
    val pendingCount: Int = 0,
    val pendingReason: String = "",
    val ratingCom: String = "",
    val ratingNet: String = "",
    val description: String = "",
    val coverUrl: String = "",
    val scrapeNote: String = "",
    /** Bounded log of successful scrape observations, newest first (see [parseHistory]). Diagnostic only. */
    val scrapeHistory: String = "",
    /** Validation codes you've acknowledged, ';'-separated. */
    val dismissed: String = "",
    val lastReadAt: Long = 0,
    val lastRefreshAt: Long = 0,
    val lastAttemptAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val editedAt: Long = System.currentTimeMillis(),
) {
    val key: String get() = nameKey(name)
}

val Novel.unread: Int get() = (currentCount - readCount).coerceAtLeast(0)
val Novel.readyToRead: Int get() = (downloadedThrough - readCount).coerceAtLeast(0)
val Novel.toDownload: Int get() = (currentCount - downloadedThrough).coerceAtLeast(0)

/** Single definition of the derived list predicates; filters (and anything else that needs them) use these. */
val Novel.isReadyToRead: Boolean get() = readyToRead > 0
val Novel.needsDownload: Boolean get() = likely != "No" && toDownload > 0 && readingStatus != "Aborted"
val Novel.needsReview: Boolean get() = pendingCount > 0

/** How the chapters split for display. Stored values are never changed to make this look consistent. */
data class Progress(val total: Int, val read: Int, val downloadedUnread: Int, val notDownloaded: Int)

/** The one progress calculation (library ring, detail bar). Clamping is for rendering only. */
fun Novel.progress(): Progress {
    val total = maxOf(currentCount, downloadedThrough, readCount, 0)
    val read = readCount.coerceIn(0, total)
    val downloadedUnread = (downloadedThrough - read).coerceIn(0, total - read)
    return Progress(total, read, downloadedUnread, total - read - downloadedUnread)
}
// ---- Scrape History: `timestamp~source~count~siteStatus~outcome[~title]` entries joined by `|`, newest first.
const val HISTORY_PER_SOURCE = 10
val HISTORY_OUTCOMES = setOf("APPLIED", "KEPT", "PENDING", "MANUAL")

data class HistoryEntry(
    val at: Long, val source: String, val count: Int, val siteStatus: String, val outcome: String, val title: String = "",
)

private fun cleanField(s: String, max: Int) = s.replace(Regex("[|~;\\r\\n]"), " ").trim().take(max)

/** @return the valid entries and how many malformed entries were dropped. */
fun parseHistory(raw: String): Pair<List<HistoryEntry>, Int> {
    if (raw.isBlank()) return emptyList<HistoryEntry>() to 0
    var bad = 0
    val out = ArrayList<HistoryEntry>()
    for (part in raw.split("|")) {
        if (part.isBlank()) continue
        val f = part.split("~")
        val at = if (f.size in 5..6) tsIn(f[0]) else 0L
        val count = f.getOrNull(2)?.trim()?.toIntOrNull()
        if (f.size !in 5..6 || at <= 0 || f[1].isBlank() || count == null || count < 0 || f[4].trim() !in HISTORY_OUTCOMES) { bad++; continue }
        out.add(HistoryEntry(at, f[1].trim(), count, f[3].trim(), f[4].trim(), f.getOrNull(5)?.trim().orEmpty()))
    }
    return out to bad
}

/** Keeps the newest [HISTORY_PER_SOURCE] entries per source (the list is newest first). */
fun List<HistoryEntry>.capPerSource(): List<HistoryEntry> {
    val seen = HashMap<String, Int>()
    return filter { e -> val k = (seen[e.source] ?: 0) + 1; seen[e.source] = k; k <= HISTORY_PER_SOURCE }
}

fun encodeHistory(list: List<HistoryEntry>): String = list.joinToString("|") {
    "${tsOut(it.at)}~${it.source}~${it.count}~${it.siteStatus}~${it.outcome}" + if (it.title.isNotEmpty()) "~${it.title}" else ""
}

/**
 * Adds this scrape's observations to [existing]. An entry is only added when its count, site status or title differs
 * from that source's newest entry, so unchanged refreshes leave the history (and the row) untouched.
 */
fun appendHistory(existing: String, titles: Map<String, String>, statuses: Map<String, String>, counts: Map<String, Int>, outcome: String, at: Long): String {
    val list = parseHistory(existing).first.toMutableList()
    var added = false
    for ((src, count) in counts) {
        val status = cleanField(statuses[src].orEmpty(), 20)
        val title = cleanField(titles[src].orEmpty(), 80)
        val newest = list.firstOrNull { it.source == src }
        if (newest != null && newest.count == count && newest.siteStatus == status && newest.title == title) continue
        list.add(0, HistoryEntry(at, cleanField(src, 20), count, status, outcome, title))
        added = true
    }
    return if (added) encodeHistory(list.capPerSource()) else existing
}

// ---- Freshness: how recently a novel was refreshed from its site (reads stored timestamps only, no network).
const val STALE_DAYS = 14

/** "today", "yesterday", "12 days ago", "3 months ago". */
fun agoText(ms: Long, now: Long = System.currentTimeMillis()): String {
    val days = ((now - ms) / 86_400_000L).coerceAtLeast(0)
    return when {
        days == 0L -> "today"
        days == 1L -> "yesterday"
        days < 60 -> "$days days ago"
        else -> "${days / 30} months ago"
    }
}

/** [level]: 0 = fine, 1 = stale or never refreshed, 2 = the last attempt failed. */
data class FreshTag(val label: String, val level: Int)

/** Null when the novel has no site link (nothing to refresh). */
fun Novel.freshnessTag(now: Long = System.currentTimeMillis()): FreshTag? = when {
    slug.isBlank() -> null
    lastAttemptAt > lastRefreshAt -> FreshTag("Refresh failed", 2)
    lastRefreshAt <= 0 -> FreshTag("Never refreshed", 1)
    (now - lastRefreshAt) / 86_400_000L >= STALE_DAYS -> FreshTag("Refreshed ${agoText(lastRefreshAt, now)}", 1)
    else -> FreshTag("Refreshed ${agoText(lastRefreshAt, now)}", 0)
}

fun Novel.ruleDownloadDefault(): Int = if (conversion && sent) currentCount else 0
fun Novel.dismissedSet(): Set<String> = dismissed.split(";").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

fun decodeCounts(s: String): Map<String, Int> =
    s.split(";").mapNotNull { p ->
        val kv = p.split("=")
        if (kv.size == 2) kv[1].trim().toIntOrNull()?.let { kv[0].trim() to it } else null
    }.toMap()

fun encodeCounts(m: Map<String, Int>): String = m.entries.joinToString(";") { "${it.key}=${it.value}" }
fun Novel.siteCountMap(): Map<String, Int> = decodeCounts(siteCounts)
fun sourceLabel(k: String): String = if (k in BASES) ".$k" else k

/** Case/punctuation-insensitive identity used for name matching. */
fun nameKey(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), "")

/** 0..1 name similarity (character bigram Dice); differing numbers (sequels) are penalised. */
fun similarity(a: String, b: String): Double {
    val x = nameKey(a); val y = nameKey(b)
    if (x.isEmpty() || y.isEmpty()) return 0.0
    if (x == y) return 1.0
    if (x.length < 2 || y.length < 2) return 0.0
    val bx = x.windowed(2).groupingBy { it }.eachCount()
    val by = y.windowed(2).groupingBy { it }.eachCount()
    var inter = 0
    for ((k, v) in bx) inter += minOf(v, by[k] ?: 0)
    var score = 2.0 * inter / ((x.length - 1) + (y.length - 1))
    val na = Regex("\\d+").findAll(a).map { it.value }.toList()
    val nb = Regex("\\d+").findAll(b).map { it.value }.toList()
    if (na != nb) score *= 0.7
    return score
}

fun today(): String = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())

fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun fmt(n: Int): String = "%,d".format(n)

/** ISO-8601 UTC for the CSV ("" when unset); accepts ISO or epoch millis on the way in. */
fun tsOut(ms: Long): String = if (ms <= 0) "" else Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()
fun tsIn(s: String): Long {
    val t = s.trim()
    if (t.isEmpty()) return 0
    t.toLongOrNull()?.let { return it }
    return try { Instant.parse(t).toEpochMilli() } catch (e: Exception) { 0 }
}

/** "spirit-realm.html", "https://novelfull.com/spirit-realm.html?page=2" -> "spirit-realm" */
fun slugFrom(input: String): String {
    val t = input.trim().substringBefore('?').substringBefore('#').trimEnd('/')
    return t.substringAfterLast('/').removeSuffix(".html")
}

/** After the read count changes, keep Status of Reading sensible. */
fun Novel.afterProgress(): Novel {
    var s = readingStatus
    if (s == "Not Started" && readCount > 0) s = "Running"
    if (currentCount > 0 && readCount >= currentCount && novelStatus == "Completed") s = "Completed"
    return if (s == readingStatus) this else copy(readingStatus = s)
}

// ------------------------------------------------------------------ chapter-count protection
data class CountDecision(val newCurrent: Int, val pending: Int, val reason: String, val note: String = "")

/**
 * Decides what to do with chapter counts found by a scrape (or carried in an imported CSV).
 *  - manual-chapter novels: never touched
 *  - preferred source set and present: that source's count is the candidate
 *  - otherwise, sources that disagree are NEVER auto-resolved: sent to review (no "take the higher")
 *  - a lower count, or (when [autoApply]) a jump bigger than max(100, 10%), is sent to review
 *  - [autoApply] = false (initial import): any difference is sent to review, nothing is overwritten
 */
fun decideCount(n: Novel, counts: Map<String, Int>, autoApply: Boolean): CountDecision {
    val cur = n.currentCount
    val keep = CountDecision(cur, 0, "")
    if (n.manualChapters) return keep.copy(note = "Manual chapters: site count skipped")
    val valid = counts.filterValues { it > 0 }
    if (valid.isEmpty()) return keep.copy(note = "${FailKind.BAD_COUNT.label} — no \"Chapter N\" links found on site")

    val pref = n.preferredSource
    var conflict = ""
    val chosen: Int
    if (pref.isNotEmpty() && pref in valid) {
        chosen = valid.getValue(pref)
    } else {
        if (valid.values.distinct().size > 1) {
            conflict = "Sources disagree: " + valid.entries.joinToString(" · ") { "${sourceLabel(it.key)} ${fmt(it.value)}" }
        }
        chosen = valid.values.max()
    }
    if (conflict.isNotEmpty()) return CountDecision(cur, chosen, conflict)
    if (chosen == cur) return keep
    if (cur == 0) return CountDecision(chosen, 0, "")

    val reason = when {
        chosen < cur -> "Site shows ${fmt(chosen)}, fewer than your ${fmt(cur)}"
        !autoApply -> "Site shows ${fmt(chosen)} (you have ${fmt(cur)})"
        chosen - cur > maxOf(100, cur / 10) -> "Large jump: ${fmt(cur)} → ${fmt(chosen)}"
        else -> ""
    }
    return if (reason.isEmpty()) CountDecision(chosen, 0, "") else CountDecision(cur, chosen, reason)
}