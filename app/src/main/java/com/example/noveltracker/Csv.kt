package com.example.noveltracker

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

enum class IssueKind(val label: String) { SKIPPED("Skipped"), CORRECTED("Corrected"), WARNING("Warning") }

/**
 * A problem found while reading a CSV. [message] is the readable one-liner; the structured fields say which cell,
 * what was found, what was expected and what the import did about it ([kind]: row skipped / value corrected / kept with a warning).
 */
data class CsvIssue(
    val row: Int, val name: String, val message: String,
    val kind: IssueKind = IssueKind.WARNING,
    val field: String = "", val value: String = "", val expected: String = "", val consequence: String = "",
)

private fun problem(row: Int, name: String, kind: IssueKind, field: String, value: String, expected: String, consequence: String) =
    CsvIssue(
        row, name, "$field ${if (value.isEmpty()) "is empty" else "'$value'"}: expected $expected ($consequence)",
        kind, field, value, expected, consequence,
    )

data class CsvResult(
    val novels: List<Novel>,
    /** Problems with individual rows/cells. */
    val issues: List<CsvIssue>,
    /** File-level observations (missing columns etc.). */
    val notes: List<String>,
    val hasEditedAt: Boolean,
)

private class Col(val name: String, val get: (Novel) -> Any)

object Csv {
    /** Novels whose chapter titles are not "Chapter N". Applied only when a CSV has no "Manual Chapters" column. */
    val DEFAULT_MANUAL = setOf(
        "ancientstrengtheningtechnique", "icanmakeeverythinglevelup", "martialworld", "thegodsfallchronicles",
    )

    // The app schema. Header and cells come from the same list, so they can't drift apart.
    private val COLS = listOf(
        Col("Stable ID") { it.uid },
        Col("Name of Series") { it.name },
        Col("Chapter Read Count") { it.readCount },
        Col("Current Chapter Count") { it.currentCount },
        Col("Downloaded Through Chapter") { it.downloadedThrough },
        Col("Downloaded Set By") { it.downloadedSource },
        Col("Chapters left") { (it.currentCount - it.readCount).coerceAtLeast(0) },
        Col("Likely to read") { it.likely },
        Col("Last Checked Chapter Count") { it.lastChecked },
        Col("Status of Reading") { it.readingStatus },
        Col("Status of Novel") { it.novelStatus },
        Col("Conversion") { if (it.conversion) "Yes" else "No" },
        Col("Sent") { if (it.sent) "Yes" else "No" },
        Col("Remarks") { it.remarks },
        Col("Manual Chapters") { if (it.manualChapters) "Yes" else "No" },
        Col("Preferred Source") { it.preferredSource },
        Col("Slug") { it.slug },
        Col("Found On") { it.sites },
        Col("Author") { it.author },
        Col("Alternative Names") { it.altNames },
        Col("Genres") { it.genres },
        Col("Source") { it.source },
        Col("Site Title") { it.siteTitle },
        Col("Site Status") { it.siteStatus },
        Col("Site Counts") { it.siteCounts },
        Col("Pending Count") { it.pendingCount },
        Col("Pending Reason") { it.pendingReason },
        Col("Rating (com)") { it.ratingCom },
        Col("Rating (net)") { it.ratingNet },
        Col("Description") { it.description },
        Col("Cover URL") { it.coverUrl },
        Col("Scrape Notes") { it.scrapeNote },
        Col("Scrape History") { it.scrapeHistory },
        Col("Dismissed Checks") { it.dismissed },
        Col("Last Read At") { tsOut(it.lastReadAt) },
        Col("Last Refresh At") { tsOut(it.lastRefreshAt) },
        Col("Last Attempt At") { tsOut(it.lastAttemptAt) },
        Col("Created At") { tsOut(it.createdAt) },
        Col("Edited At") { tsOut(it.editedAt) },
    )
    val HEADER: List<String> = COLS.map { it.name }

    fun decode(bytes: ByteArray): String {
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            dec.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (e: CharacterCodingException) {
            String(bytes, charset("windows-1252"))
        }
    }

    /** RFC 4180 parser; delimiter (comma or tab) is detected from the header line. */
    fun parse(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull() ?: ""
        val delim = if (firstLine.count { it == '\t' } > firstLine.count { it == ',' }) '\t' else ','
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else inQuotes = false
                } else sb.append(c)
            } else if (c == '"') {
                inQuotes = true
            } else if (c == delim) {
                row.add(sb.toString()); sb.setLength(0)
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                row.add(sb.toString()); sb.setLength(0)
                if (row.any { it.isNotBlank() }) rows.add(row)
                row = ArrayList()
            } else sb.append(c)
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) {
            row.add(sb.toString())
            if (row.any { it.isNotBlank() }) rows.add(row)
        }
        return rows
    }

    private fun yes(s: String) = s.equals("yes", true) || s.equals("true", true) || s.equals("y", true) || s == "1"
    private fun isYesNo(s: String) = s.lowercase() in setOf("yes", "no", "true", "false", "y", "n", "1", "0")
    private fun validDate(s: String): Boolean = try {
        SimpleDateFormat("dd-MM-yyyy", Locale.US).apply { isLenient = false }.parse(s) != null
    } catch (e: Exception) { false }

    /** Reads a CSV in the app schema, your original sheet, or the Python enrichment output. Never throws on bad cells. */
    fun toNovels(rows: List<List<String>>): CsvResult {
        if (rows.isEmpty()) return CsvResult(emptyList(), listOf(CsvIssue(0, "", "File is empty")), emptyList(), false)
        val h = rows[0].map { it.trim().lowercase() }
        fun col(vararg names: String): Int {
            for (n in names) { val i = h.indexOf(n); if (i >= 0) return i }
            return -1
        }
        val cName = col("name of series", "name", "title")
        if (cName < 0) {
            return CsvResult(emptyList(), listOf(CsvIssue(1, "", "No 'Name of Series' column. Found: ${rows[0].joinToString()}")), emptyList(), false)
        }
        val cId = col("stable id", "stable ids", "uid", "id")
        val cRead = col("chapter read count"); val cCur = col("current chapter count")
        val cDl = listOf(col("downloaded through chapter"), col("downloaded chapter count"), col("downloaded till")).filter { it >= 0 }
        val cDlSrc = col("downloaded set by")
        val cLikely = col("likely to read"); val cLast = col("last checked chapter count", "last checked")
        val cRs = col("status of reading"); val cNs = col("status of novel")
        val cConv = col("conversion"); val cSent = col("sent"); val cRem = col("remarks")
        val cManual = col("manual chapters"); val cPref = col("preferred source")
        val cSlug = col("slug"); val cFound = col("found on"); val cUrlCom = col("url (com)"); val cUrlNet = col("url (net)")
        val cAuthor = col("author"); val cAlt = col("alternative names"); val cGenres = col("genres"); val cSource = col("source")
        val cSiteTitle = col("site title"); val cSiteStatus = col("site status")
        val cSiteCounts = col("site counts"); val cChCom = col("chapters (com)"); val cChNet = col("chapters (net)")
        val cSiteCnt = col("site chapter count")
        val cPend = col("pending count"); val cPendWhy = col("pending reason")
        val cRatCom = col("rating (com)"); val cRatNet = col("rating (net)"); val cDesc = col("description")
        val cCover = col("cover url"); val cNote = col("scrape notes", "scrape note"); val cDismissed = col("dismissed checks")
        val cReadAt = col("last read at"); val cRefAt = col("last refresh at"); val cAttAt = col("last attempt at")
        val cCreated = col("created at"); val cEdited = col("edited at")
        val cHist = col("scrape history")

        val notes = ArrayList<String>()
        if (cId < 0) notes.add("No Stable ID column: new IDs are generated and existing rows are matched by name.")
        if (cEdited < 0) notes.add("No Edited At column: when merging, existing rows are kept (their read progress is only ever raised).")
        if (cDl.isEmpty()) notes.add("No downloaded column: Downloaded Through is set by rule (Converted and Sent both Yes → current count, else 0).")
        if (cManual < 0) notes.add("No Manual Chapters column: the four built-in manual-chapter novels are flagged.")

        val now = System.currentTimeMillis()
        val out = ArrayList<Novel>(); val issues = ArrayList<CsvIssue>()
        val seenKeys = HashSet<String>(); val seenIds = HashSet<String>()
        var generated = 0

        for ((i, r) in rows.drop(1).withIndex()) {
            val rowNo = i + 2
            fun s(c: Int) = if (c in r.indices) r[c].trim() else ""
            val name = s(cName)
            if (name.isEmpty()) {
                if (r.any { it.isNotBlank() }) issues.add(problem(rowNo, "", IssueKind.SKIPPED, "Name of Series", "", "a series name", "row skipped"))
                continue
            }
            val key = nameKey(name)
            if (!seenKeys.add(key)) { issues.add(problem(rowNo, name, IssueKind.SKIPPED, "Name of Series", name, "a name that is unique in the file", "row skipped, the first one is kept")); continue }

            fun parseCount(raw: String, label: String): Int {
                if (raw.isEmpty()) return 0
                val n = raw.replace(",", "").trim().toIntOrNull()
                if (n == null) { issues.add(problem(rowNo, name, IssueKind.CORRECTED, label, raw, "a whole number", "using 0")); return 0 }
                if (n < 0) { issues.add(problem(rowNo, name, IssueKind.CORRECTED, label, raw, "0 or more", "using 0")); return 0 }
                return n
            }
            fun count(c: Int, label: String) = parseCount(s(c), label)
            fun pick(c: Int, options: List<String>, def: String, label: String): String {
                val raw = s(c)
                if (raw.isEmpty()) return def
                val m = options.firstOrNull { it.equals(raw, true) }
                if (m == null) issues.add(problem(rowNo, name, IssueKind.CORRECTED, label, raw, options.joinToString(" / "), "using $def"))
                return m ?: def
            }
            fun yn(c: Int, label: String): Boolean {
                val raw = s(c)
                if (raw.isNotEmpty() && !isYesNo(raw)) issues.add(problem(rowNo, name, IssueKind.CORRECTED, label, raw, "Yes or No", "using No"))
                return yes(raw)
            }

            var id = s(cId)
            if (id.isEmpty()) { id = UUID.randomUUID().toString(); generated++ }
            else if (!seenIds.add(id)) {
                issues.add(problem(rowNo, name, IssueKind.CORRECTED, "Stable ID", id, "an ID not used by an earlier row", "a new ID was generated"))
                id = UUID.randomUUID().toString()
            }
            seenIds.add(id)

            val sheetCurrent = count(cCur, "Current Chapter Count")
            val conv = yn(cConv, "Conversion"); val sent = yn(cSent, "Sent")
            val manual = if (cManual >= 0) yes(s(cManual)) else key in DEFAULT_MANUAL

            // Downloaded: an explicit value is preserved (flagged later if it disagrees with the rule); else the rule.
            val dlCell = cDl.map { s(it) }.firstOrNull { it.isNotEmpty() } ?: ""
            val downloaded: Int
            val dlSource: String
            if (dlCell.isNotEmpty()) {
                downloaded = parseCount(dlCell, "Downloaded Through Chapter")
                dlSource = s(cDlSrc).lowercase().takeIf { it in DL_SOURCES } ?: "sheet"
            } else {
                downloaded = if (conv && sent) sheetCurrent else 0
                dlSource = "rule"
            }

            val lastChecked = s(cLast)
            if (lastChecked.isNotEmpty() && !validDate(lastChecked)) {
                issues.add(problem(rowNo, name, IssueKind.WARNING, "Last Checked Chapter Count", lastChecked, "a date as dd-MM-yyyy", "kept as written"))
            }

            // Per-source site counts: app format, else Python's per-site columns, else the merged Site Chapter Count.
            val counts = LinkedHashMap<String, Int>()
            if (cSiteCounts >= 0 && s(cSiteCounts).isNotEmpty()) {
                counts.putAll(decodeCounts(s(cSiteCounts)))
            } else {
                if (cChCom >= 0) count(cChCom, "Chapters (com)").takeIf { it > 0 }?.let { counts["com"] = it }
                if (cChNet >= 0) count(cChNet, "Chapters (net)").takeIf { it > 0 }?.let { counts["net"] = it }
                if (counts.isEmpty() && cSiteCnt >= 0) count(cSiteCnt, "Site Chapter Count").takeIf { it > 0 }?.let { counts["site"] = it }
            }

            // Scrape History: bad entries are dropped (and reported), over-long histories keep the newest per source.
            val (histEntries, histBad) = parseHistory(s(cHist))
            if (histBad > 0) issues.add(problem(rowNo, name, IssueKind.CORRECTED, "Scrape History", "$histBad entries", "timestamp~source~count~status~outcome", "malformed entries dropped"))
            val history = encodeHistory(histEntries.capPerSource())

            var n = Novel(
                uid = id, name = name, readCount = count(cRead, "Chapter Read Count"), currentCount = sheetCurrent,
                downloadedThrough = downloaded, downloadedSource = dlSource,
                likely = pick(cLikely, LIKELY, "Yes", "Likely to read"), lastChecked = lastChecked,
                readingStatus = pick(cRs, READING, "Not Started", "Status of Reading"),
                novelStatus = pick(cNs, NOVEL_STATUS, "Running", "Status of Novel"),
                conversion = conv, sent = sent, remarks = s(cRem), manualChapters = manual,
                preferredSource = s(cPref).lowercase(),
                slug = s(cSlug).ifEmpty { slugFrom(s(cUrlCom).ifEmpty { s(cUrlNet) }) },
                sites = s(cFound).ifEmpty {
                    listOf("com" to s(cUrlCom), "net" to s(cUrlNet)).filter { it.second.isNotEmpty() }.joinToString(",") { it.first }
                },
                author = s(cAuthor), altNames = s(cAlt), genres = s(cGenres), source = s(cSource),
                siteTitle = s(cSiteTitle), siteStatus = s(cSiteStatus),
                siteCounts = if (manual) "" else encodeCounts(counts),
                ratingCom = s(cRatCom), ratingNet = s(cRatNet), description = s(cDesc), coverUrl = s(cCover),
                scrapeNote = s(cNote), scrapeHistory = history, dismissed = s(cDismissed),
                lastReadAt = tsIn(s(cReadAt)), lastRefreshAt = tsIn(s(cRefAt)), lastAttemptAt = tsIn(s(cAttAt)),
                createdAt = tsIn(s(cCreated)).takeIf { it > 0 } ?: now,
                editedAt = tsIn(s(cEdited)).takeIf { it > 0 } ?: now,
            )

            // Chapter counts: never silently replaced on import. A saved pending proposal is restored as-is;
            // otherwise any difference between your count and the site's becomes a review item.
            if (cPend >= 0 && s(cPend).isNotEmpty()) {
                n = n.copy(pendingCount = count(cPend, "Pending Count"), pendingReason = s(cPendWhy))
            } else if (counts.isNotEmpty() && !manual) {
                val d = decideCount(n, counts, autoApply = false)
                n = n.copy(pendingCount = d.pending, pendingReason = d.reason)
            }
            out.add(n)
        }
        if (cId >= 0 && generated > 0) notes.add("$generated rows had no Stable ID; new ones were generated.")
        return CsvResult(out, issues, notes, hasEditedAt = cEdited >= 0)
    }

    fun export(list: List<Novel>): ByteArray {
        fun q(s: String) =
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",") { q(it) }).append("\r\n")
        for (n in list.sortedBy { it.name.lowercase() }) {
            sb.append(COLS.joinToString(",") { q(it.get(n).toString()) }).append("\r\n")
        }
        // UTF-8 BOM so Excel opens it correctly
        return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sb.toString().toByteArray(Charsets.UTF_8)
    }
}