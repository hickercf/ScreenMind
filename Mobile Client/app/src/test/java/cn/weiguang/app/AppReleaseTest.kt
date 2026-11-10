package cn.weiguang.app

import cn.weiguang.app.data.AppRelease
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppReleaseTest {
    private fun manifest() = JSONObject().put("schema_version", 1).put("package_name", "cn.weiguang.app")
        .put("channel", "debug").put("version_code", 4).put("version_name", "0.4.0")
        .put("sha256", "a".repeat(64)).put("size_bytes", 100).put("min_sdk", 29)
        .put("notes", "Update").put("download_url", "")

    @Test fun localDevelopmentAndRemoteAssets() {
        val local = AppRelease.parse(manifest(), "http://127.0.0.1:8765", "debug", true)
        assertTrue(local.url.startsWith("http://127.0.0.1:8765/v1/app/releases/"))
        val remote = AppRelease.parse(manifest().put("download_url", "https://cdn.example.org/a.apk"), "https://api.example.org", "debug", false)
        assertEquals("https://cdn.example.org/a.apk", remote.url)
        assertEquals(4L, remote.code)
    }

    @Test fun invalidContractsAreRejected() {
        val invalid = listOf("schema_version" to 2, "package_name" to "other", "channel" to "pilot",
            "sha256" to "bad", "size_bytes" to AppRelease.MAX_SIZE + 1, "min_sdk" to 20)
        invalid.forEach { (key, value) ->
            assertThrows(IllegalArgumentException::class.java) {
                AppRelease.parse(manifest().put(key, value), "https://api.example.org", "debug", false)
            }
        }
    }

    @Test fun unsafeRemoteUrlsAndProductionHttpRejected() {
        listOf("http://cdn.example.org/a.apk", "https://user:secret@cdn.example.org/a.apk", "file:///a.apk").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                AppRelease.parse(manifest().put("download_url", url), "http://127.0.0.1:8765", "debug", true)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppRelease.parse(manifest(), "http://127.0.0.1:8765", "debug", false)
        }
    }
}
