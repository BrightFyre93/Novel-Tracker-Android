package com.example.noveltracker

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.coroutines.resume

val BASES = mapOf("com" to "https://novelfull.com", "net" to "https://novelfull.net")

/** Shared by the hidden scraper WebView and the visible "Verify in browser" screen so cookies/UA match. */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.applyScrapeSettings(blockImages: Boolean) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.userAgentString = settings.userAgentString.replace("; wv", "")
    settings.blockNetworkImage = blockImages
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
}

/** Why a refresh failed. Shown as the start of the failure note (queue row, detail footer, snackbar). */
enum class FailKind(val label: String) {
    NOT_FOUND("Not found"), BLOCKED("Bot check"), TIMEOUT("Timed out"),
    NETWORK("Network error"), PARSE("Couldn't read page"), BAD_COUNT("No chapter count"),
}

sealed interface Fetch {
    data class Ok(val html: String) : Fetch
    data object NotFound : Fetch
    data object Blocked : Fetch
    data class Error(val msg: String, val kind: FailKind = FailKind.NETWORK) : Fetch
}

/** Loads a page in an off-screen WebView (a real browser engine, so bot checks usually pass). */
class WebFetcher(private val app: Context) {
    suspend fun fetch(url: String, timeoutMs: Long = 40_000): Fetch = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val wv = WebView(app)
            var finished = false
            var sawChallenge = false
            val started = SystemClock.uptimeMillis()

            fun finish(r: Fetch) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                wv.stopLoading()
                handler.post { wv.destroy() }
                if (cont.isActive) cont.resume(r)
            }

            fun poll() {
                if (finished) return
                if (SystemClock.uptimeMillis() - started > timeoutMs) {
                    finish(if (sawChallenge) Fetch.Blocked else Fetch.Error("timed out", FailKind.TIMEOUT))
                    return
                }
                wv.evaluateJavascript(
                    "document.readyState+'|||'+document.title+'|||'+document.documentElement.outerHTML"
                ) { raw ->
                    if (!finished) {
                        val s = try { JSONTokener(raw).nextValue() as? String } catch (e: Exception) { null }
                        val p = s?.split("|||", limit = 3)
                        var done = false
                        if (p != null && p.size == 3) {
                            val t = p[1].lowercase()
                            val challenge = "just a moment" in t || "attention required" in t || "verify you are human" in t
                            if (challenge) sawChallenge = true
                            else if (p[0] == "complete" && p[2].length > 3000) { finish(Fetch.Ok(p[2])); done = true }
                        }
                        if (!done && !finished) handler.postDelayed({ poll() }, 1000)
                    }
                }
            }

            wv.applyScrapeSettings(blockImages = true)
            wv.layout(0, 0, 1080, 1920)
            wv.webViewClient = object : WebViewClient() {
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame && errorResponse.statusCode == 404) finish(Fetch.NotFound)
                }

                // No connection, DNS failure, etc. Without this the load just sat there until the 40 s timeout.
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) finish(Fetch.Error("network error (${error.description})", FailKind.NETWORK))
                }
            }
            cont.invokeOnCancellation { handler.post { finish(Fetch.Error("cancelled")) } }
            wv.loadUrl(url)
            handler.postDelayed({ poll() }, 1500)
        }
    }
}

object NovelParser {
    data class Page(
        val titles: List<String>,
        val author: String, val alt: List<String>, val genres: List<String>, val source: String,
        val status: String, val rating: String, val description: String, val cover: String, val latest: Int,
        /** The link text the newest chapter number was read from (for diagnosing a wrong count). */
        val latestText: String = "",
    )

    private val genericHeadings = setOf(
        "novel info", "latest chapters", "chapter list", "summary", "comments", "related novels", "same author",
        "more from author", "hot fantasy novel", "hot novel", "novel list", "genre", "genres", "description",
    )

    private fun pageTitles(doc: org.jsoup.nodes.Document): List<String> {
        val raw = ArrayList<String>()
        doc.selectFirst("h1")?.text()?.trim()?.let { raw.add(it) }
        doc.select("h3.title, h2.title").take(3).forEach { raw.add(it.text().trim()) }
        doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.let { raw.add(it) }
        if (doc.title().isNotBlank()) raw.add(doc.title().trim())
        return raw.filter { it.isNotEmpty() && it.lowercase() !in genericHeadings }
            .flatMap { listOf(it) + it.split(Regex("\\s+[-\u2013|]\\s+")) }
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private val labelKeys = mapOf(
        "author" to "author", "authors" to "author",
        "alternative names" to "alt", "alternative name" to "alt", "alternative" to "alt",
        "genre" to "genres", "genres" to "genres", "source" to "source", "status" to "status",
    )
    private val navClasses = setOf("navbar", "navigation", "menu", "breadcrumb", "dropdown-menu")
    private val chapterRe = Regex("^\\s*(?:chapter|ch\\.?)\\s*(\\d[\\d,]*)", RegexOption.IGNORE_CASE)
    private val ratingRe = Regex("""(\d+(?:\.\d+)?)\s*/\s*(5|10)\s*(?:from|\()?\s*([\d,]+)""")

    private fun inNav(e: Element) = e.parents().any { p ->
        p.tagName() == "nav" || p.tagName() == "header" || p.classNames().any { it in navClasses }
    }

    private fun normStatus(s: String): String {
        val t = s.lowercase()
        return when {
            "complete" in t -> "Completed"
            "ongoing" in t || "running" in t -> "Running"
            else -> s.trim()
        }
    }

    /** Port of the Python parser; works on both novelfull.com and novelfull.net layouts. */
    fun parse(html: String, base: String, slug: String = ""): Page {
        val doc = Jsoup.parse(html, base)
        var author = ""; var alt = ""; var source = ""; var status = ""
        var genres = emptyList<String>()

        // "Label: value" blocks (novelfull.com)
        for (el in doc.select("h3, h4, strong, b, span, label, dt, div")) {
            if (el.children().isNotEmpty()) continue
            val lab = el.text().trim()
            val key = labelKeys[lab.trimEnd(':').trim().lowercase()] ?: continue
            val parent = el.parent() ?: continue
            val v = parent.text().replaceFirst(lab, "").trim().trim(',', ' ')
            if (v.isEmpty() || v.length > 400) continue
            when (key) {
                "genres" -> if (genres.isEmpty()) {
                    val links = parent.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }
                    val vals = links.ifEmpty { v.split(",").map { it.trim() }.filter { it.isNotEmpty() } }
                    if (vals.size in 1..15) genres = vals
                }
                "author" -> if (author.isEmpty()) author = v
                "alt" -> if (alt.isEmpty()) alt = v
                "source" -> if (source.isEmpty()) source = v
                "status" -> if (status.isEmpty()) status = v
            }
        }
        // link-based fallbacks (novelfull.net)
        if (author.isEmpty()) {
            author = doc.select("a[href*='/author/']").filter { !inNav(it) }.map { it.text().trim() }
                .filter { it.isNotEmpty() }.distinct().take(4).joinToString(", ")
        }
        if (genres.isEmpty()) {
            for (a in doc.select("a[href*='/genre/']")) {
                if (inNav(a)) continue
                val p = a.parent() ?: continue
                val v = p.select("a[href*='/genre/']").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
                if (v.size in 1..15) { genres = v; break }
            }
        }
        if (status.isEmpty()) {
            status = doc.select("a, span, li, div").firstOrNull {
                it.children().isEmpty() && !inNav(it) && it.text().trim().lowercase() in setOf("completed", "ongoing")
            }?.text()?.trim().orEmpty()
        }

        // description
        var desc = ""
        for (sel in listOf("div.desc-text", "div.desc-text-full", "#tab-description", "div.summary",
            "[itemprop=description]", "div.description", "div.book-intro")) {
            val el = doc.selectFirst(sel) ?: continue
            val paras = el.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
            var t = if (paras.isNotEmpty()) paras.joinToString("\n\n") else el.text().trim()
            t = t.replace(Regex("(?i)(see more\\s*»?|see all)\\s*$"), "").trim()
                .replace(Regex("(?i)^summary\\s*:?\\s*"), "")
            if (t.length > 60) { desc = t; break }
        }
        if (desc.isEmpty()) {
            desc = doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim().orEmpty()
        }

        val rating = ratingRe.find(doc.text())?.let { "${it.groupValues[1]}/${it.groupValues[2]} (${it.groupValues[3]})" }.orEmpty()
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".book img, img[itemprop=image]")?.absUrl("src").orEmpty()

        // newest chapter = highest "Chapter N" link text (titles not starting with "Chapter N" are not counted)
        var latest = 0
        var latestText = ""
        for (a in doc.select("a")) {
            val num = chapterRe.find(a.text())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: continue
            if (num > latest) { latest = num; latestText = a.text().trim().take(60) }
        }
        // Fallback for pages whose link text doesn't start with "Chapter N" (e.g. "Vol 2 Chapter 5", or just a title):
        // read the number from this novel's own chapter URLs, /<slug>/chapter-123-....html. Other novels' links are ignored.
        if (latest == 0 && slug.isNotBlank()) {
            val hrefRe = Regex("/${Regex.escape(slug)}/[^/]*?chapter[-_](\\d+)", RegexOption.IGNORE_CASE)
            latest = doc.select("a[href]").mapNotNull { hrefRe.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        }

        return Page(
            titles = pageTitles(doc),
            author = author, alt = alt.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() },
            genres = genres, source = source, status = normStatus(status), rating = rating,
            description = desc, cover = cover, latest = latest, latestText = latestText,
        )
    }
}

data class Scraped(
    val ok: Boolean, val blocked: Boolean, val note: String,
    /** Set when [ok] is false: the main reason the refresh failed. */
    val kind: FailKind? = null,
    /** Newest-chapter number per source ("com", "net", ...). 0 = the page had no recognisable chapter links. */
    val counts: Map<String, Int> = emptyMap(),
    /** Per-source observations, used only for Scrape History. */
    val titlesBySource: Map<String, String> = emptyMap(),
    val statusBySource: Map<String, String> = emptyMap(),
    val siteStatus: String = "", val siteTitle: String = "",
    val author: String = "", val alt: String = "", val genres: String = "",
    val source: String = "", val ratingCom: String? = null, val ratingNet: String? = null,
    val description: String = "", val cover: String = "",
)

class Scraper(ctx: Context) {
    private val web = WebFetcher(ctx.applicationContext)

    suspend fun fetch(n: Novel): Scraped {
        if (n.slug.isBlank()) return Scraped(ok = false, blocked = false, note = "No link set")
        val sites = n.sites.split(",").map { it.trim() }.filter { it in BASES }.ifEmpty { BASES.keys.toList() }
        val pages = linkedMapOf<String, NovelParser.Page>()
        val notes = ArrayList<String>()
        val fails = ArrayList<FailKind>()
        var blocked = false
        for ((i, s) in sites.withIndex()) {
            if (i > 0) delay(1200)
            when (val r = web.fetch("${BASES.getValue(s)}/${n.slug}.html")) {
                is Fetch.Ok -> {
                    val page = NovelParser.parse(r.html, BASES.getValue(s), n.slug)
                    if (page.titles.isEmpty() && page.latest == 0 && page.description.isEmpty()) {
                        notes.add("$s: couldn't read the page"); fails.add(FailKind.PARSE)
                    } else {
                        pages[s] = page
                        // Say what the page looked like, so a missing count can be diagnosed from the note alone.
                        if (page.latest == 0) notes.add("$s: loaded \"${page.titles.firstOrNull().orEmpty().take(40)}\" (${r.html.length} chars) but found no chapter links")
                        // A decimal chapter ("Chapter 1356.1") counts as its whole number; say so, so the raw text is visible.
                        else if (Regex("\\d\\.\\d").containsMatchIn(page.latestText.take(20))) notes.add("$s: latest link \"${page.latestText}\" counted as ${page.latest}")
                    }
                }
                is Fetch.NotFound -> { notes.add("$s: page not found"); fails.add(FailKind.NOT_FOUND) }
                is Fetch.Blocked -> { notes.add("$s: bot check - use Verify in browser"); blocked = true; fails.add(FailKind.BLOCKED) }
                is Fetch.Error -> { notes.add("$s: ${r.msg}"); fails.add(r.kind) }
            }
            if (blocked) break
        }
        if (pages.isEmpty()) {
            val kind = if (blocked) FailKind.BLOCKED else fails.firstOrNull()
            val detail = notes.joinToString("; ")
            return Scraped(ok = false, blocked = blocked, kind = kind, note = if (kind != null) "${kind.label} — $detail" else detail)
        }
        val ps = pages.values.toList()
        val bestTitle = ps.flatMap { it.titles }.maxByOrNull { similarity(n.name, it) }.orEmpty()
        val statuses = ps.map { it.status }.filter { it.isNotBlank() }
        return Scraped(
            ok = true, blocked = false, note = notes.joinToString("; "),
            counts = pages.mapValues { it.value.latest },
            titlesBySource = pages.mapValues { (_, p) -> p.titles.maxByOrNull { similarity(n.name, it) }.orEmpty() },
            statusBySource = pages.mapValues { it.value.status },
            siteStatus = if ("Completed" in statuses) "Completed" else statuses.firstOrNull().orEmpty(),
            siteTitle = bestTitle,
            author = ps.firstOrNull { it.author.isNotBlank() }?.author.orEmpty(),
            alt = ps.flatMap { it.alt }.distinctBy { it.lowercase() }.joinToString("; "),
            genres = ps.flatMap { it.genres }.distinctBy { it.lowercase() }.joinToString("; "),
            source = ps.firstOrNull { it.source.isNotBlank() }?.source.orEmpty(),
            ratingCom = pages["com"]?.rating, ratingNet = pages["net"]?.rating,
            description = ps.maxByOrNull { it.description.length }?.description.orEmpty(),
            cover = ps.firstOrNull { it.cover.isNotBlank() }?.cover.orEmpty(),
        )
    }
}

/**
 * Applies a scrape. Only scrape-owned fields change: metadata, site status/title/counts, timestamps.
 * Your progress, downloaded count, statuses and remarks are never touched, and the chapter count only changes
 * automatically when [decideCount] says it is safe; anything else becomes a pending review item.
 */
fun Novel.applyScrape(s: Scraped, now: Long = System.currentTimeMillis()): Novel {
    if (!s.ok) return copy(scrapeNote = s.note, lastAttemptAt = now)
    val decided = decideCount(this, s.counts, autoApply = true)
    // You chose "Keep my count" for exactly these site numbers: don't ask again until the site's numbers change.
    val sig = countsSignature(s.counts)
    val d = if (decided.pending > 0 && decided.newCurrent == currentCount && sig.isNotEmpty() && sig == keptSignature())
        decided.copy(pending = 0, reason = "", note = "Site shows ${fmt(decided.pending)}; you chose to keep ${fmt(currentCount)}")
    else decided
    val notes = listOf(s.note, d.note).filter { it.isNotEmpty() }
    val outcome = when {
        manualChapters -> "MANUAL"
        d.pending > 0 -> "PENDING"
        d.newCurrent != currentCount -> "APPLIED"
        else -> "KEPT"
    }
    val history = appendHistory(scrapeHistory, s.titlesBySource, s.statusBySource, s.counts, outcome, now)
    return copy(
        currentCount = d.newCurrent, pendingCount = d.pending, pendingReason = d.reason,
        siteCounts = if (manualChapters) "" else encodeCounts(s.counts.filterValues { it > 0 }),
        siteStatus = s.siteStatus.ifBlank { siteStatus }, siteTitle = s.siteTitle.ifBlank { siteTitle },
        author = s.author.ifBlank { author }, altNames = s.alt.ifBlank { altNames },
        genres = s.genres.ifBlank { genres }, source = s.source.ifBlank { source },
        ratingCom = s.ratingCom ?: ratingCom, ratingNet = s.ratingNet ?: ratingNet,
        description = s.description.ifBlank { description }, coverUrl = s.cover.ifBlank { coverUrl },
        lastChecked = today(), lastRefreshAt = now, lastAttemptAt = now,
        scrapeNote = notes.joinToString("; "), scrapeHistory = history,
    )
}