package com.example.noveltracker

enum class Severity { WARN, INFO }
data class Issue(val code: String, val severity: Severity, val message: String)

val ISSUE_TITLES = mapOf(
    "READ_GT_CURRENT" to "Read count is above the latest chapter",
    "DL_GT_CURRENT" to "Downloaded-through is above the latest chapter",
    "DL_SHEET_CONFLICT" to "Recorded download disagrees with Converted/Sent",
    "READING_NOT_STARTED" to "Marked Not Started but chapters are read",
    "READING_COMPLETED_UNREAD" to "Marked Completed but chapters are unread",
    "TITLE_MISMATCH" to "Site page title doesn't match the name",
    "STATUS_MISMATCH" to "Your status differs from the site's",
    "NO_METADATA" to "Refreshed but no description or genres found",
)

/** Live checks (nothing here is stored except the codes you dismissed). Used after imports and after scrapes. */
fun Novel.issues(includeDismissed: Boolean = false): List<Issue> {
    val out = ArrayList<Issue>()
    if (currentCount > 0 && readCount > currentCount)
        out.add(Issue("READ_GT_CURRENT", Severity.WARN, "Read ${fmt(readCount)} is above the latest count ${fmt(currentCount)}."))
    if (currentCount > 0 && downloadedThrough > currentCount)
        out.add(Issue("DL_GT_CURRENT", Severity.WARN, "Downloaded through ${fmt(downloadedThrough)} is above the latest count ${fmt(currentCount)}."))
    if (downloadedSource == "sheet" && downloadedThrough != ruleDownloadDefault()) {
        fun yn(b: Boolean) = if (b) "Yes" else "No"
        out.add(Issue(
            "DL_SHEET_CONFLICT", Severity.WARN,
            "Your sheet records downloaded through ${fmt(downloadedThrough)}, but Converted=${yn(conversion)} and Sent=${yn(sent)} " +
                "would give ${fmt(ruleDownloadDefault())}. The recorded value was kept.",
        ))
    }
    if (readingStatus == "Not Started" && readCount > 0)
        out.add(Issue("READING_NOT_STARTED", Severity.WARN, "Status is Not Started but ${fmt(readCount)} chapters are read."))
    if (readingStatus == "Completed" && novelStatus == "Completed" && currentCount > 0 && readCount < currentCount)
        out.add(Issue("READING_COMPLETED_UNREAD", Severity.WARN, "Marked Completed but ${fmt(currentCount - readCount)} chapters are unread."))
    if (siteTitle.isNotBlank() && similarity(name, siteTitle) < 0.6)
        out.add(Issue("TITLE_MISMATCH", Severity.WARN, "The site page is titled “$siteTitle”. Check the link."))
    if (siteStatus in NOVEL_STATUS && siteStatus != novelStatus)
        out.add(Issue("STATUS_MISMATCH", Severity.INFO, "The site says $siteStatus; you have $novelStatus. They are kept separate."))
    if (slug.isNotBlank() && lastRefreshAt > 0 && description.isBlank() && genres.isBlank())
        out.add(Issue("NO_METADATA", Severity.INFO, "The last refresh found no description or genres."))
    return if (includeDismissed) out else out.filter { it.code !in dismissedSet() }
}
