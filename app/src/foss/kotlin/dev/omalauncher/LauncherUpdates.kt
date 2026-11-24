package dev.omalauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import dev.omalauncher.net.httpGet
import dev.omalauncher.net.openHttp
import dev.omalauncher.system.installerOf
import dev.omalauncher.system.openUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException

/**
 * A newer launcher on GitHub releases, for settings' updates box: the GitHub and F-Droid build's twin of the play
 * build's LauncherUpdates. GitHub is asked when settings opens (at most hourly) and on its "check", never in the
 * background. "update" downloads the release's APK and hands it to Android's installer, which asks before it
 * replaces the launcher; Android checks the APK is signed with the same key as the installed one. Without an APK
 * in the release, or without a way to install it, it opens the release's page instead. An install an F-Droid client
 * made never asks: F-Droid ships each release some days later and updates it itself.
 */
class LauncherUpdates(
    private val context: Context,
) {
    private val fromFdroid = installerOf(context) in FDROID_CLIENTS
    private val scope = MainScope()

    /** The newer release, from the last check. */
    private var release: Release? = null

    /** GitHub has a newer release. */
    var available by mutableStateOf(false)
        private set

    var checking by mutableStateOf(false)
        private set

    /** The last download failed; "update" tries again. */
    var failed by mutableStateOf(false)
        private set

    /** The APK's download progress in percent while it downloads, else null. */
    var downloading by mutableStateOf<Int?>(null)
        private set

    /** When GitHub last answered, 0 before it has; settings shows it as "up to date · checked …". */
    var checkedAt by mutableStateOf(0L)
        private set

    /** The newer release's version, like "1.5.0 (build 12)". */
    var version by mutableStateOf<String?>(null)
        private set

    /** Asks GitHub unless it was asked within the hour; [force] asks anyway. */
    fun check(force: Boolean = false) {
        if (checking || downloading != null) return
        if (!force && fromFdroid) return
        if (!force && System.currentTimeMillis() - checkedAt < CHECK_EVERY) return
        checking = true
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { String(httpGet(RELEASES)) }
                val newer = newerRelease(json, BuildConfig.VERSION_NAME, BuildConfig.CI_BUILD)
                release = newer
                version = newer?.label
                available = newer != null
                failed = false
                checkedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                // Offline or rate-limited: nothing to show, as without an update; the next settings visit asks again.
                Log.w(TAG, "checking GitHub for a release failed", e)
            } finally {
                checking = false
            }
        }
    }

    /** Downloads the newer release's APK and opens Android's installer on it. */
    @Suppress("UNUSED_PARAMETER")
    fun start(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val r = release ?: return
        if (downloading != null) return
        val apk = r.apk
        if (apk == null) return openPage(r)
        // Android asks the user once to let the launcher install apps; after that, "update" again installs.
        if (!context.packageManager.canRequestPackageInstalls()) {
            val allow = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(allow) }.isFailure) openPage(r)
            return
        }
        failed = false
        downloading = 0
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { download(apk) { downloading = it } }
                install(file)
            } catch (e: Exception) {
                Log.w(TAG, "downloading $apk failed", e)
                failed = true
            } finally {
                downloading = null
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onResult(resultCode: Int) {}

    private fun openPage(r: Release) {
        // Without a browser there's nothing to open it in; mustn't crash the launcher.
        if (!context.openUrl(r.page)) Log.w(TAG, "no app opens ${r.page}")
    }

    /** Into the cache, where only this download lives: an earlier one is replaced, and Android may clear it any time. */
    private fun download(
        url: String,
        progress: (Int) -> Unit,
    ): File {
        val dir = File(context.cacheDir, UPDATE_DIR).apply { deleteRecursively(); mkdirs() }
        val part = File(dir, "launcher.apk.part")
        val c = openHttp(url, connectTimeout = 15_000, readTimeout = 30_000)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for $url")
            val total = c.contentLengthLong
            var read = 0L
            var shown = -1
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val pct = if (total > 0) (read * 100 / total).toInt() else 0
                        if (pct != shown) {
                            shown = pct
                            scope.launch { progress(pct) }
                        }
                    }
                }
            }
            if (total > 0 && read != total) throw IOException("got $read of $total bytes")
        } finally {
            c.disconnect()
        }
        return File(dir, "launcher.apk").also { if (!part.renameTo(it)) throw IOException("couldn't keep the download") }
    }

    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** A release of this launcher on GitHub: its version, the CI build that made it (0 for none), its page and its APK. */
    data class Release(
        val version: String,
        val build: Int,
        val page: String,
        val apk: String?,
    ) {
        val label get() = if (build > 0) "$version ($build)" else version
    }

    internal companion object {
        const val TAG = "LauncherUpdates"
        const val RELEASES = "https://api.github.com/repos/dhawan9898/my_launcher/releases?per_page=20"
        const val CHECK_EVERY = 60 * 60_000L
        const val UPDATE_DIR = "update"
        const val APK_TYPE = "application/vnd.android.package-archive"

        /** F-Droid and the clients of its repositories. */
        val FDROID_CLIENTS = setOf("org.fdroid.fdroid", "org.fdroid.basic", "com.looker.droidify", "com.machiav3lli.fdroid")

        /** v1.12.0, 1.12.0, or v1.12.0-build.11 / ci.11 as CI tags each build from main. */
        private val TAG_VERSION = Regex("""v?(\d+(?:\.\d+)*)(?:[-._](?:build|ci)\.?(\d+))?""")

        /**
         * The newest release in GitHub's releases [json] that's newer than [current] built by CI build [build]:
         * a higher version, or the same version from a later build.
         */
        fun newerRelease(
            json: String,
            current: String,
            build: Int = 0,
        ): Release? {
            val releases = JSONArray(json)
            val found =
                (0 until releases.length()).mapNotNull { i ->
                    val r = releases.getJSONObject(i)
                    if (r.optBoolean("draft") || r.optBoolean("prerelease")) return@mapNotNull null
                    val tagName = r.optString("tag_name")
                    val m = TAG_VERSION.matchEntire(tagName) ?: return@mapNotNull null
                    val assets = r.optJSONArray("assets")
                    val apk =
                        assets?.let { a ->
                            (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }?.optString("browser_download_url")
                        }
                    Release(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 0, r.optString("html_url"), apk?.takeIf { it.isNotEmpty() })
                }
            return found.filter { compare(it, current, build) > 0 }.maxWithOrNull { a, b -> compare(a, b.version, b.build) }
        }

        private fun compare(
            r: Release,
            version: String,
            build: Int,
        ) = compareVersions(r.version, version).takeIf { it != 0 } ?: r.build.compareTo(build)

        /** Compares dotted versions part by part as numbers: 1.10.0 is after 1.9.2; a missing part is 0. */
        fun compareVersions(
            a: String,
            b: String,
        ): Int {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}
