package io.github.bropines.tailscaled.core

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What the share target reads for another app, and which of the app's own files it will not. */
class ShareGuardTest {
    private lateinit var root: File
    private lateinit var data: File
    private lateinit var shared: File
    private lateinit var own: OwnPlaces

    private val files = "io.github.bropines.tailscaled.fileprovider"
    private val documents = "io.github.bropines.tailscaled.documents"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("shareguard").toFile()
        data = File(root, "data/app").apply { File(this, "files/states/default").mkdirs() }
        shared = File(root, "sdcard").apply { mkdirs() }
        File(data, "files/states/default/tailscaled.state").writeText("key")
        File(shared, "photo.jpg").writeText("jpg")
        own = OwnPlaces(listOf(data), files, documents)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun file(path: String) = ShareGuard.allows("file", null, path, path.split('/').filter { it.isNotEmpty() }, own)

    private fun content(authority: String, vararg segments: String) =
        ShareGuard.allows("content", authority, segments.joinToString("/", prefix = "/"), segments.toList(), own)

    @Test
    fun aFileOutsideTheAppIsRead() {
        assertTrue(file(File(shared, "photo.jpg").path))
        assertTrue("not there yet is not ours either", file(File(shared, "missing.bin").path))
    }

    @Test
    fun aFileOfTheAppIsNot() {
        assertFalse(file(File(data, "files/states/default/tailscaled.state").path))
        assertFalse(file(data.path))
        assertFalse("by a way round", file("${shared.path}/../data/app/files/states/default/tailscaled.state"))
    }

    @Test
    fun aLinkIntoTheAppIsFollowed() {
        val link = File(shared, "innocent")
        Files.createSymbolicLink(link.toPath(), File(data, "files").toPath())
        assertFalse(file("${link.path}/states/default/tailscaled.state"))
    }

    @Test
    fun aDirectoryWithALongerNameIsNotTheApps() {
        val neighbour = File(root, "data/app.other").apply { mkdirs() }
        assertTrue(file(File(neighbour, "a.txt").path))
    }

    @Test
    fun theProcessAsAFileIsRefused() {
        assertFalse(file("/proc/self/environ"))
        assertFalse(file("/proc/self/root/etc/hosts"))
        assertFalse(ShareGuard.allows("file", null, null, emptyList(), own))
    }

    @Test
    fun anotherAppsProviderAnswersForItself() {
        assertTrue(content("com.android.providers.media.documents", "document", "image:42"))
        assertTrue(content("com.example.fileprovider", "root", "data", "app", "files", "x"))
        assertTrue(ShareGuard.allows("https", "example.com", "/a", listOf("a"), own))
    }

    @Test
    fun ofTheFileProviderOnlyWhatTheAppSharesItself() {
        assertTrue(content(files, "taildrop", "default", "photo.jpg"))
        assertTrue(content(files, "exports", "taildrop-history.csv"))
        assertTrue("a user of the device named in the authority", content("10@$files", "exports", "taildrop-history.csv"))
        assertFalse(content(files, "updates", "app.apk"))
        assertFalse(content("10@$files", "updates", "app.apk"))
        assertFalse(content(files, "files", "states", "default", "tailscaled.state"))
        assertFalse(content(files))
    }

    @Test
    fun theStorageRootIsJudgedByTheFileItNames() {
        val external = OwnPlaces(listOf(data, File("/storage/emulated/0/Android/data/io.github.bropines.tailscaled")), files, documents)
        fun storage(vararg rest: String) = ShareGuard.allows("content", files, null, listOf("storage") + rest, external)
        assertTrue(storage("emulated", "0", "Download", "film.mp4"))
        assertFalse(storage("emulated", "0", "Android", "data", "io.github.bropines.tailscaled", "files", "Download", "app.apk"))
        assertFalse(storage("emulated", "0", "Download", "..", "Android", "data", "io.github.bropines.tailscaled", "cache", "x"))
        assertFalse("out of /storage and into the process", storage("..", "proc", "self", "environ"))
    }

    @Test
    fun ofTheDocumentsProviderOnlyADocumentItGivesOut() {
        assertTrue(content(documents, "document", "file:default/photo.jpg"))
        assertTrue(content(documents, "document", "drive_path:default:/home.tailnet/laptop/share/film.mp4"))
        assertTrue(content(documents, "tree", "drive_path:default:/home.tailnet/laptop/share", "document", "drive_path:default:/home.tailnet/laptop/share/film.mp4"))
        assertFalse(content(documents, "document", "drive_path:default:/home.tailnet/laptop/share/../../desktop/x"))
        assertFalse(content(documents, "document", "root"))
        assertFalse(content(documents, "root", "taildrive_root"))
        assertFalse(content(documents, "tree", "account:default"))
        assertEquals(false, content(documents))
    }
}
