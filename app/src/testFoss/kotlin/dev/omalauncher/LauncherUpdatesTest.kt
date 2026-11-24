package dev.omalauncher

import dev.omalauncher.LauncherUpdates.Companion.compareVersions
import dev.omalauncher.LauncherUpdates.Companion.newerRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherUpdatesTest {
    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        apk: String? = null,
    ): String {
        val assets = apk?.let { """[{"name":"$it","browser_download_url":"https://github.com/x/releases/download/$tag/$it"}]""" } ?: "[]"
        return """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"html_url":"https://github.com/x/releases/tag/$tag","assets":$assets}"""
    }

    private fun releases(vararg r: String) = r.joinToString(",", "[", "]")

    @Test fun findsTheNewestNewerRelease() {
        val json = releases(release("v1.4.0"), release("v1.6.0"), release("v1.5.0"))
        val r = newerRelease(json, "1.4.0")!!
        assertEquals("1.6.0", r.version)
        assertEquals("https://github.com/x/releases/tag/v1.6.0", r.page)
        assertNull(r.apk)
    }

    @Test fun nothingWhenUpToDateOrAhead() {
        val json = releases(release("v1.4.0"), release("v1.3.0"))
        assertNull(newerRelease(json, "1.4.0"))
        // A build from main after a release, with its version already bumped.
        assertNull(newerRelease(json, "1.5.0"))
    }

    @Test fun skipsDraftsPrereleasesAndOtherTags() {
        val json = releases(release("v2.0.0", draft = true), release("v1.9.0", prerelease = true), release("mesa-zink-26.2.4"), release("v1.4.0"))
        assertNull(newerRelease(json, "1.4.0"))
    }

    @Test fun ciBuildsOfOneVersionCountByBuild() {
        val json = releases(release("v1.12.0-build.11", apk = "a.apk"), release("v1.12.0-build.13", apk = "b.apk"), release("v1.12.0-build.12"))
        val r = newerRelease(json, "1.12.0", build = 11)!!
        assertEquals(13, r.build)
        assertEquals("1.12.0 (13)", r.label)
        assertEquals("https://github.com/x/releases/download/v1.12.0-build.13/b.apk", r.apk)
        assertNull(newerRelease(json, "1.12.0", build = 13))
        // A higher version wins over a later build of a lower one.
        assertEquals("1.13.0", newerRelease(releases(release("v1.12.0-build.20"), release("v1.13.0-build.14")), "1.12.0", 11)!!.version)
    }

    @Test fun comparesVersionPartsAsNumbers() {
        assertTrue(compareVersions("1.10.0", "1.9.2") > 0)
        assertTrue(compareVersions("0.20.0", "1.0.0") < 0)
        assertEquals(0, compareVersions("1.4", "1.4.0"))
    }
}
