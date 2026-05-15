package com.mythara.ui.launcher

/**
 * Heuristic classifier that buckets an installed-app package name
 * into "Work" vs "Personal" for the spotlight drawer's auto-grouped
 * sections.
 *
 * Three signals, in priority order:
 *   1. Exact-package match against a curated list of Google
 *      Workspace + Microsoft 365 + popular third-party productivity
 *      apps (Slack, Notion, Asana, etc.) — these are unambiguous.
 *   2. Keyword match in the package name (e.g. "outlook", "teams",
 *      "office") — catches OEM-rebranded variants.
 *   3. Fallback — Personal.
 *
 * Heuristic by design: we'd rather misclassify a borderline
 * productivity app as Personal than annoy the user by burying their
 * personal calendar in the Work folder. The bucket is for finding
 * speed, not policy enforcement.
 */
object WorkspaceDetector {

    enum class Bucket { Work, Personal }

    fun bucketOf(pkg: String): Bucket {
        val lower = pkg.lowercase()
        if (lower in WORK_EXACT) return Bucket.Work
        if (WORK_KEYWORDS.any { lower.contains(it) }) return Bucket.Work
        return Bucket.Personal
    }

    /** Exact package names that always belong to Work. */
    private val WORK_EXACT = setOf(
        // Google Workspace
        "com.google.android.gm",                          // Gmail
        "com.google.android.calendar",                    // Calendar
        "com.google.android.apps.docs",                   // Drive
        "com.google.android.apps.docs.editors.docs",      // Docs
        "com.google.android.apps.docs.editors.sheets",    // Sheets
        "com.google.android.apps.docs.editors.slides",    // Slides
        "com.google.android.apps.meetings",               // Meet
        "com.google.android.apps.dynamite",               // Google Chat
        "com.google.android.keep",                        // Keep
        "com.google.android.apps.tasks",                  // Google Tasks

        // Microsoft 365
        "com.microsoft.office.outlook",
        "com.microsoft.office.word",
        "com.microsoft.office.excel",
        "com.microsoft.office.powerpoint",
        "com.microsoft.office.onenote",
        "com.microsoft.teams",
        "com.microsoft.todos",
        "com.microsoft.skydrive",                          // OneDrive
        "com.microsoft.sharepoint",

        // Apple iWork (in case running through emulation, future)
        "com.apple.iwork.pages",
        "com.apple.iwork.numbers",
        "com.apple.iwork.keynote",

        // Other widely-used productivity / collaboration
        "com.slack",
        "com.Slack",
        "notion.id",
        "com.asana.app",
        "com.atlassian.android.jira.core",
        "com.atlassian.android.confluence",
        "com.linear",
        "com.github.android",
        "com.gitlab",
        "us.zoom.videomeetings",
        "com.cisco.webex.meetings",
        "com.airbnb.android.lottie",
        "com.figma.mirror",
        "com.miro.android",
        "com.dropbox.android",
        "com.box.android",
        "com.salesforce.chatter",
        "com.workday",
        "com.servicenow.app",
        "com.amazon.workdocs",
    )

    /** Keyword fragments that, when present in a package name,
     *  bucket it as Work. Lowercased before comparison. */
    private val WORK_KEYWORDS = setOf(
        "outlook",
        "office",
        "teams",
        "sharepoint",
        "onedrive",
        "googleworkspace",
        "salesforce",
        "workday",
        "servicenow",
        "atlassian",
        "confluence",
        "jira",
        "linear",
        "asana",
        "monday.app",
        "notion",
        "miro",
        "figma",
        "zoom",
        "webex",
        "gotomeeting",
        "rocketchat",
        "mattermost",
    )
}
