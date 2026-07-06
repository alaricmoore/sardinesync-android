package com.sardinetracker.sync

/**
 * Fixed, non-secret configuration. The per-user values (server URL, bearer
 * token, account id) are NOT here — they're supplied by the user on first run
 * and stored encrypted; see [SecureConfig] and [SettingsActivity]. Keeping the
 * secrets out of source is what lets this repo be public.
 */
object Config {
    /** API path appended to the user's server URL for health-sync POSTs. */
    const val API_PATH = "/api/health-sync"

    /**
     * Sentinel path the web app's "sync" nav link points at. The WebView
     * intercepts navigation to it and runs a native Health Connect sync instead
     * of letting it hit the server (mirrors the iOS /ios/sync-healthkit bridge).
     */
    const val SYNC_SENTINEL_PATH = "/android/sync-healthconnect"

    /** Like SYNC_SENTINEL_PATH, but for the "import history" nav link → backfill. */
    const val BACKFILL_SENTINEL_PATH = "/android/backfill-healthconnect"

    /** Wall-clock time for the automatic daily background sync (24h). */
    const val SYNC_HOUR = 23
    const val SYNC_MINUTE = 0

    /** How many days of history the "Backfill past data" menu action imports. */
    const val BACKFILL_DAYS = 365
}
