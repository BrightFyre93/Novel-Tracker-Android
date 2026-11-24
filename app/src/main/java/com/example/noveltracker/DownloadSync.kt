package com.example.noveltracker

/** One novel that would get a new downloaded-through number. [matchedBy] says how the line was matched to the library. */
data class DownloadUpdate(val uid: String, val name: String, val from: Int, val to: Int, val matchedBy: String)

/** What pasting Windows-app text would do. Nothing has been changed when you have this. */
data class DownloadSyncPlan(
    /** Matched novels whose downloaded number would change (raised or lowered). */
    /** Matched novels whose downloaded number would change (raised or lowered). */
    val updates: List<DownloadUpdate>,
    /** Matched novels that already have that number. */
    val unchanged: Int,
    /** Lines that matched no novel in the library. */
    val notFound: List<String>,
    /** Lines that could not be read (missing or invalid number, empty line of cells). */
    val badLines: List<String>,
) {
    val raises: Int get() = updates.count { it.to > it.from }
    val drops: Int get() = updates.count { it.to < it.from }
}

/**
 * Reads text copied from the Windows app: one line per novel, "Name,Downloaded" at the least.
 *
 * - First line is a header when it holds any known column name; otherwise lines are read as Name, Downloaded.
 * - Columns (header names, any case): Name | Title, Downloaded | Downloaded through | Last chapter,
 *   optional Slug | Link | URL, optional Stable ID | ID.
 * - Cells may be separated by commas, semicolons or tabs (pasted from a spreadsheet); "quoted, cells" are supported.
 * - A line is matched to a novel by Stable ID first, then by page name (slug), then by name (ignoring case and punctuation).
 * - A decimal such as 101.1 means chapter 101.
 */
object DownloadSync {
    private val nameHeads = setOf("name", "title", "name of series", "series", "novel")
    private val numHeads = setOf("downloaded", "downloaded through", "downloaded through chapter", "download", "downloads", "last chapter", "chapter")
    private val slugHeads = setOf("slug", "page name", "link", "url")
    private val idHeads = setOf("id", "stable id", "uid")

    private class Line(val id: String, val slug: String, val name: String, val downloaded: Int)

    /** Splits one line at [d], honouring "quoted cells" and "" as an escaped quote. */
    private fun split(line: String, d: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == d && !quoted -> { out.add(sb.toString().trim()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString().trim())
        return out
    }

    /** "101.1" -> 101, "1,356" -> 1356, anything else -> null. */
    private fun wholeNumber(raw: String): Int? = raw.replace(",", "").trim().substringBefore('.').toIntOrNull()?.takeIf { it >= 0 }

    fun plan(text: String, library: List<Novel>): DownloadSyncPlan {
        val rows = text.lines().map { it.trim('\uFEFF', ' ', '\r') }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val bad = ArrayList<String>()
        if (rows.isEmpty()) return DownloadSyncPlan(emptyList(), 0, emptyList(), listOf("Nothing pasted"))

        val first = rows[0]
        val d = when {
            '\t' in first -> '\t'
            first.count { it == ';' } > first.count { it == ',' } -> ';'
            else -> ','
        }
        val head = split(first, d).map { it.lowercase().trim() }
        val isHeader = head.any { it in nameHeads || it in numHeads || it in slugHeads || it in idHeads }
        val nameIdx = if (isHeader) head.indexOfFirst { it in nameHeads } else 0
        val numIdx = if (isHeader) head.indexOfFirst { it in numHeads } else 1
        val slugIdx = if (isHeader) head.indexOfFirst { it in slugHeads } else -1
        val idIdx = if (isHeader) head.indexOfFirst { it in idHeads } else -1
        if (numIdx < 0) return DownloadSyncPlan(emptyList(), 0, emptyList(), listOf("No \"Downloaded\" column in the first line"))
        if (nameIdx < 0 && slugIdx < 0 && idIdx < 0) return DownloadSyncPlan(emptyList(), 0, emptyList(), listOf("Need a Name, Slug or Stable ID column"))

        val lines = ArrayList<Line>()
        for ((i, row) in rows.withIndex()) {
            if (isHeader && i == 0) continue
            val c = split(row, d)
            fun cell(ix: Int) = if (ix in c.indices) c[ix] else ""
            val n = wholeNumber(cell(numIdx))
            if (n == null) { bad.add("\"${row.take(60)}\": no valid chapter number"); continue }
            val line = Line(cell(idIdx), slugFrom(cell(slugIdx)), cell(nameIdx), n)
            if (line.id.isBlank() && line.slug.isBlank() && line.name.isBlank()) { bad.add("\"${row.take(60)}\": no name, link or ID"); continue }
            lines.add(line)
        }

        val byUid = library.associateBy { it.uid }
        val bySlug = library.filter { it.slug.isNotBlank() }.associateBy { it.slug }
        val byKey = library.associateBy { it.key }
        val target = LinkedHashMap<String, Pair<Int, String>>() // uid -> highest number seen, how it matched
        val notFound = ArrayList<String>()
        for (l in lines) {
            val hit: Pair<Novel, String>? = byUid[l.id]?.let { it to "ID" }
                ?: bySlug[l.slug]?.let { it to "link" }
                ?: (if (l.name.isNotBlank()) byKey[nameKey(l.name)]?.let { it to "name" } else null)
            if (hit == null) { notFound.add(l.name.ifBlank { l.slug.ifBlank { l.id } }); continue }
            val prev = target[hit.first.uid]
            if (prev == null || l.downloaded > prev.first) target[hit.first.uid] = l.downloaded to hit.second
        }

        val updates = ArrayList<DownloadUpdate>()
        var unchanged = 0
        for ((uid, t) in target) {
            val n = byUid.getValue(uid)
            if (n.downloadedThrough == t.first) unchanged++ else updates.add(DownloadUpdate(uid, n.name, n.downloadedThrough, t.first, t.second))
        }
        updates.sortBy { it.name.lowercase() }
        return DownloadSyncPlan(updates, unchanged, notFound, bad)
    }
}
