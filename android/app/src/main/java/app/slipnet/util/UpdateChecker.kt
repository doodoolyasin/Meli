package app.slipnet.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String
)

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val GITHUB_API = "https://api.github.com/repos/doodoolyasin/Meli/releases"
    private const val TIMEOUT_MS = 10_000
    // Only check once per 12 hours
    const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    /**
     * Check GitHub for a newer release. Returns null if up-to-date or on error.
     * Safe to call from any coroutine — uses Dispatchers.IO, never throws.
     */
    suspend fun check(currentVersionName: String): AppUpdate? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(GITHUB_API).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "AryanaNG-Android")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS

            if (conn.responseCode != 200) {
                Log.d(TAG, "GitHub API returned ${conn.responseCode}")
                conn.disconnect()
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val releasesList = mutableListOf<JSONObject>()
            try {
                val array = org.json.JSONArray(body)
                for (i in 0 until array.length()) {
                    releasesList.add(array.getJSONObject(i))
                }
            } catch (_: Exception) {
                releasesList.add(JSONObject(body))
            }

            var bestVer = ""
            var bestUrl = ""
            var bestNotes = ""

            val verRegex = Regex("""v?(\d+\.\d+\.\d+)""")

            for (json in releasesList) {
                val tagName = json.optString("tag_name", "").removePrefix("v").trim()
                val htmlUrl = json.optString("html_url", "")
                val releaseBody = json.optString("body", "").take(500)

                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i)
                        val name = asset?.optString("name", "") ?: ""
                        val downloadUrl = asset?.optString("browser_download_url", "") ?: ""
                        if (name.endsWith(".apk") && downloadUrl.isNotBlank()) {
                            val match = verRegex.find(name)
                            val assetVer = match?.groupValues?.get(1) ?: tagName
                            if (assetVer.isNotBlank()) {
                                if (bestVer.isBlank() || isNewer(assetVer, bestVer) ||
                                    (assetVer == bestVer && (name.contains("arm64") || name.contains("universal")))) {
                                    bestVer = assetVer
                                    bestUrl = downloadUrl
                                    bestNotes = releaseBody
                                }
                            }
                        }
                    }
                }

                if (tagName.isNotBlank() && (bestVer.isBlank() || isNewer(tagName, bestVer))) {
                    bestVer = tagName
                    if (bestUrl.isBlank()) bestUrl = htmlUrl
                    bestNotes = releaseBody
                }
            }

            if (bestVer.isBlank()) {
                return@withContext null
            }

            if (isNewer(bestVer, currentVersionName)) {
                Log.d(TAG, "Update available: $bestVer (current: $currentVersionName)")
                AppUpdate(
                    versionName = bestVer,
                    downloadUrl = bestUrl,
                    releaseNotes = bestNotes
                )
            } else {
                Log.d(TAG, "Up to date ($currentVersionName)")
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /**
     * Compare version strings. Returns true if remote > current.
     * Handles: "2.2.1", "2.3-beta", "2.3.0-rc1"
     * Pre-release tags (beta, rc, alpha) are considered older than the
     * same version without a tag: 2.3-beta < 2.3
     */
    private fun isNewer(remote: String, current: String): Boolean {
        // Strip build-variant suffixes (e.g. "-lite") — they aren't version indicators
        val cleanCurrent = current.removeSuffix("-lite")
        val (rNums, rPre) = parseVersion(remote)
        val (cNums, cPre) = parseVersion(cleanCurrent)
        for (i in 0 until maxOf(rNums.size, cNums.size)) {
            val rv = rNums.getOrElse(i) { 0 }
            val cv = cNums.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        // Same numeric version: release > pre-release
        // e.g., "2.3" (no pre) is newer than "2.3-beta"
        if (cPre != null && rPre == null) return true
        if (cPre == null && rPre != null) return false
        return false
    }

    /** Split "2.3.1-beta" into ([2,3,1], "beta"). */
    private fun parseVersion(version: String): Pair<List<Int>, String?> {
        val dashIndex = version.indexOf('-')
        val numPart = if (dashIndex >= 0) version.substring(0, dashIndex) else version
        val prePart = if (dashIndex >= 0) version.substring(dashIndex + 1) else null
        val nums = numPart.split(".").map { it.toIntOrNull() ?: 0 }
        return nums to prePart
    }
}
