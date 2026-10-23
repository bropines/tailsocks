package io.github.bropines.tailscaled.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The queue Taildrive downloads run in, without Android: how many files are read at once and
 * whose, the order kept, a cancel of a download that waits or runs, the counts with files side by
 * side, names claimed by files that want the same one at the same moment, and a node that goes.
 * The share is in memory; its files are read in small pieces, and some wait at a gate the test
 * opens, which is how "in flight" is held still long enough to look at.
 */
class TaildriveDownloadQueueTest {

    private val temp: File = Files.createTempDirectory("queue").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val parent = DriveLocation(listOf("tail.ts.net", "nas", "Movies"))

    @After
    fun cleanUp() {
        scope.cancel()
        temp.deleteRecursively()
    }

    // --- the world --------------------------------------------------------------------------------

    /** A file of the share: its bytes, the size its reader claims, a gate its reads wait at, a failure at its opening. */
    private class Spec(
        val bytes: ByteArray,
        val claimed: Long = bytes.size.toLong(),
        val gate: CountDownLatch? = null,
        val failOpen: (() -> Exception)? = null,
    )

    private inner class World(@Volatile var slotCount: Int) : DownloadWorld {
        val files = ConcurrentHashMap<String, Spec>()
        val folders = ConcurrentHashMap<String, List<DriveEntry>>()
        /** The files opened, in the order they were. */
        val opened: MutableList<String> = Collections.synchronizedList(ArrayList())
        val open = AtomicInteger()
        val mostOpen = AtomicInteger()
        val listed = AtomicInteger()
        @Volatile
        var running = true
        val ended: MutableList<DownloadState> = Collections.synchronizedList(ArrayList())
        val sinksMade = AtomicInteger()
        var sinkOf: (ReceiveFolder) -> DownloadSink = { DirSink((it as ReceiveFolder.Dir).dir) }

        override fun slots() = slotCount
        override fun now() = System.currentTimeMillis()
        override fun sink(folder: ReceiveFolder): DownloadSink = sinkOf(folder).also { sinksMade.incrementAndGet() }
        override fun label(folder: ReceiveFolder): String? = null
        override fun nodeRunning() = running
        override fun ended(state: DownloadState) {
            ended += state
        }

        override fun source(request: DownloadRequest) = object : DownloadSource {
            override fun list(at: DriveLocation): DriveResult<List<DriveEntry>> {
                listed.incrementAndGet()
                return folders[at.davPath]?.let { DriveResult.Ok(it) } ?: DriveResult.Err(DriveError.AccessDenied)
            }

            override fun open(path: String): DriveFileReader {
                opened += path
                val spec = files[path] ?: throw RangeNotFoundException()
                spec.failOpen?.let { throw it() }
                return Reader(spec)
            }
        }

        /** Gives out a few bytes at a time; one that waits at its gate gives up when it is closed. */
        private inner class Reader(private val spec: Spec) : DriveFileReader {
            private var position = 0
            @Volatile
            private var closed = false

            init {
                val now = open.incrementAndGet()
                mostOpen.accumulateAndGet(now, ::maxOf)
            }

            override val size: Long = spec.claimed

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                spec.gate?.let { gate -> while (!gate.await(5, TimeUnit.MILLISECONDS)) if (closed) throw IOException("closed") }
                if (closed) throw IOException("closed")
                if (position >= spec.bytes.size) return -1
                val n = min(min(length, spec.bytes.size - position), PIECE)
                System.arraycopy(spec.bytes, position, buffer, offset, n)
                position += n
                return n
            }

            override fun close() {
                if (closed) return
                closed = true
                open.decrementAndGet()
            }
        }
    }

    private fun path(vararg names: String) = DriveLocation(parent.segments + names).davPath

    private fun bytes(size: Int, seed: Int = size) = ByteArray(size) { (it * 31 + seed).toByte() }

    private fun file(name: String, size: Long = 0) = DriveEntry(name, DriveMime.of(name).kind, size)
    private fun folder(name: String) = DriveEntry(name, DriveKind.FOLDER)

    /** [names] of [world]'s share, [size] bytes each, waiting at [gate] if there is one. */
    private fun share(world: World, names: List<String>, size: Int = 64, gate: CountDownLatch? = null): List<DriveEntry> =
        names.map { name ->
            world.files[path(name)] = Spec(bytes(size, name.hashCode()), gate = gate)
            file(name, size.toLong())
        }

    private fun request(entries: List<DriveEntry>, into: File = temp, walked: Walked? = null) =
        DownloadRequest("account", parent, entries, ReceiveFolder.Dir(into), pickedNow = false, walked = walked)

    private fun queue(world: World) = DownloadQueue(world, scope)

    private fun DownloadQueue.state(id: Int): DownloadState? = downloads.value.firstOrNull { it.id == id }

    private fun await(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            if (System.currentTimeMillis() > until) fail("timed out waiting for $what")
            Thread.sleep(2)
        }
    }

    private fun DownloadQueue.awaitStage(id: Int, stage: DownloadStage) =
        await("download $id to be $stage, it is ${state(id)?.stage}") { state(id)?.stage == stage }

    /** Long enough for a slot that is wrongly given to be taken. */
    private fun settle() = Thread.sleep(150)

    // --- slots ------------------------------------------------------------------------------------

    @Test
    fun noMoreFilesAreReadAtOnceThanThereAreSlots() {
        val world = World(slotCount = 2)
        val gate = CountDownLatch(1)
        val queue = queue(world)
        val id = queue.add(request(share(world, (1..5).map { "f$it.bin" }, gate = gate)), "Movies")
        await("two files open") { world.opened.size == 2 }
        settle()
        assertEquals(2, world.opened.size)
        assertEquals(2, queue.state(id)!!.writing.size)
        gate.countDown()
        queue.awaitStage(id, DownloadStage.DONE)
        assertEquals(2, world.mostOpen.get())
        val done = queue.state(id)!!
        assertEquals(5, done.saved)
        assertEquals(5, done.filesDone)
        assertTrue(done.writing.isEmpty())
        for (i in 1..5) assertTrue(File(temp, "f$i.bin").readBytes().contentEquals(world.files.getValue(path("f$i.bin")).bytes))
    }

    @Test
    fun theNextDownloadTakesOnlyTheSlotsTheOneBeforeItLeaves() {
        val world = World(slotCount = 3)
        val gateA = CountDownLatch(1)
        val gateB = CountDownLatch(1)
        val gateC = CountDownLatch(1)
        val queue = queue(world)
        val a = queue.add(request(share(world, listOf("a1"), gate = gateA)), "Movies")
        val b = queue.add(request(share(world, listOf("b1", "b2", "b3"), gate = gateB)), "Movies")
        val c = queue.add(request(share(world, listOf("c1"), gate = gateC)), "Movies")
        await("three files open") { world.opened.size == 3 }
        settle()
        // One file of the first leaves two slots, which the second takes; the third waits. (Files
        // that start together open in whatever order their threads come to it.)
        assertEquals(setOf(path("a1"), path("b1"), path("b2")), world.opened.toSet())
        assertEquals(DownloadStage.WAITING, queue.state(c)!!.stage)
        gateA.countDown()
        queue.awaitStage(a, DownloadStage.DONE)
        await("b3 open") { world.opened.size == 4 }
        settle()
        // The slot goes to the second download's last file, not to the third download.
        assertEquals(path("b3"), world.opened[3])
        assertEquals(4, world.opened.size)
        assertEquals(DownloadStage.WAITING, queue.state(c)!!.stage)
        gateB.countDown()
        queue.awaitStage(b, DownloadStage.DONE)
        await("c1 open") { world.opened.size == 5 }
        gateC.countDown()
        queue.awaitStage(c, DownloadStage.DONE)
        assertEquals(3, world.mostOpen.get())
    }

    @Test
    fun withOneSlotEverythingGoesInTheOrderAsked() {
        val world = World(slotCount = 1)
        val queue = queue(world)
        val a = queue.add(request(share(world, listOf("a1", "a2", "a3"))), "Movies")
        val b = queue.add(request(share(world, listOf("b1", "b2"))), "Movies")
        queue.awaitStage(a, DownloadStage.DONE)
        queue.awaitStage(b, DownloadStage.DONE)
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2").map { path(it) }, world.opened.toList())
        assertEquals(1, world.mostOpen.get())
        // They ended in that order too, and the page says which ended last.
        assertTrue(queue.state(a)!!.ended < queue.state(b)!!.ended)
    }

    @Test
    fun moreSlotsStartMoreFilesAtOnce() {
        val world = World(slotCount = 1)
        val gate = CountDownLatch(1)
        val queue = queue(world)
        val id = queue.add(request(share(world, listOf("x1", "x2", "x3", "x4"), gate = gate)), "Movies")
        await("one file open") { world.opened.size == 1 }
        world.slotCount = 3
        queue.slotsChanged()
        await("three files open") { world.opened.size == 3 }
        settle()
        assertEquals(3, world.opened.size)
        gate.countDown()
        queue.awaitStage(id, DownloadStage.DONE)
        assertEquals(4, queue.state(id)!!.saved)
    }

    // --- cancel -----------------------------------------------------------------------------------

    @Test
    fun aWaitingDownloadThatIsCancelledLeavesTheQueue() {
        val world = World(slotCount = 1)
        val gate = CountDownLatch(1)
        val queue = queue(world)
        val a = queue.add(request(share(world, listOf("a1"), gate = gate)), "Movies")
        val b = queue.add(request(share(world, listOf("b1"))), "Movies")
        await("a1 open") { world.opened.size == 1 }
        assertEquals(DownloadStage.WAITING, queue.state(b)!!.stage)
        queue.cancel(b)
        assertNull(queue.state(b))
        gate.countDown()
        queue.awaitStage(a, DownloadStage.DONE)
        settle()
        assertEquals(listOf(path("a1")), world.opened.toList())
        assertEquals(listOf(a), queue.downloads.value.map { it.id })
        // It never began, so nothing is said of its end.
        assertEquals(listOf(a), world.ended.map { it.id })
    }

    @Test
    fun aRunningDownloadThatIsCancelledClosesItsFilesAndKeepsWhatWasSaved() {
        val world = World(slotCount = 2)
        val never = CountDownLatch(1)
        val queue = queue(world)
        val entries = share(world, listOf("a1")) + share(world, listOf("a2", "a3", "a4"), gate = never)
        val a = queue.add(request(entries), "Movies")
        val b = queue.add(request(share(world, listOf("b1"))), "Movies")
        await("a1 saved and two files held") { queue.state(a)!!.let { it.saved == 1 && it.writing.size == 2 } }
        assertEquals(DownloadStage.WAITING, queue.state(b)!!.stage)
        queue.cancel(a)
        queue.awaitStage(a, DownloadStage.CANCELLED)
        val cancelled = queue.state(a)!!
        assertEquals(1, cancelled.saved)
        // A cancel is not a failure, and the files it cut short are taken away.
        assertTrue(cancelled.failures.isEmpty())
        assertTrue(File(temp, "a1").isFile)
        assertFalse(File(temp, "a2").exists())
        assertFalse(File(temp, "a3").exists())
        assertFalse(path("a4") in world.opened)
        // The queue goes on.
        queue.awaitStage(b, DownloadStage.DONE)
        assertEquals(0, world.open.get())
    }

    @Test
    fun cancelAllEndsWhatRunsAndEmptiesTheQueue() {
        val world = World(slotCount = 1)
        val never = CountDownLatch(1)
        val queue = queue(world)
        val a = queue.add(request(share(world, listOf("a1"), gate = never)), "Movies")
        val b = queue.add(request(share(world, listOf("b1"))), "Movies")
        await("a1 open") { world.opened.size == 1 }
        queue.cancelAll()
        queue.awaitStage(a, DownloadStage.CANCELLED)
        assertNull(queue.state(b))
        assertFalse(queue.active)
    }

    @Test
    fun finishedDownloadsAreClearedAndActiveOnesStay() {
        val world = World(slotCount = 1)
        val gate = CountDownLatch(1)
        val queue = queue(world)
        val a = queue.add(request(share(world, listOf("a1"))), "Movies")
        queue.awaitStage(a, DownloadStage.DONE)
        val b = queue.add(request(share(world, listOf("b1"), gate = gate)), "Movies")
        val c = queue.add(request(share(world, listOf("c1"))), "Movies")
        await("b1 open") { world.opened.size == 2 }
        queue.dismiss(b)
        queue.dismissFinished()
        assertEquals(listOf(b, c), queue.downloads.value.map { it.id })
        gate.countDown()
        queue.awaitStage(c, DownloadStage.DONE)
        queue.dismiss(b)
        assertEquals(listOf(c), queue.downloads.value.map { it.id })
    }

    // --- counts -----------------------------------------------------------------------------------

    @Test
    fun theCountsAddUpWithFilesSideBySide() {
        val world = World(slotCount = 4)
        val queue = queue(world)
        val missing = HashSet<String>()
        val short = HashSet<String>()
        val entries = (1..30).map { i ->
            val name = "file$i.bin"
            val content = bytes(i * 37, i)
            when {
                // Not there any more.
                i % 5 == 0 -> {
                    world.files[path(name)] = Spec(content, failOpen = { RangeNotFoundException() })
                    missing += name
                }
                // The server promises more than it sends.
                i % 7 == 0 -> {
                    world.files[path(name)] = Spec(content, claimed = content.size + 10L)
                    short += name
                }
                // A server that says no size, under a listing that said none either.
                i % 3 == 0 -> world.files[path(name)] = Spec(content, claimed = -1)
                else -> world.files[path(name)] = Spec(content)
            }
            file(name, if (i % 3 == 0) 0 else content.size.toLong())
        }
        val id = queue.add(request(entries), "Movies")
        queue.awaitStage(id, DownloadStage.DONE)
        val done = queue.state(id)!!
        assertEquals(30, done.files)
        assertEquals(30, done.filesDone)
        assertEquals(30 - missing.size - short.size, done.saved)
        assertEquals(
            missing.map { it to DownloadFailReason.NOT_FOUND }.toSet() + short.map { it to DownloadFailReason.INCOMPLETE },
            done.failures.map { it.path to it.reason }.toSet()
        )
        // Every byte counted once, whatever each file came to.
        assertEquals(done.bytes, done.bytesDone)
        assertEquals(1f, done.fraction)
        assertTrue(world.mostOpen.get() <= 4)
        for (i in 1..30) {
            val name = "file$i.bin"
            val saved = File(temp, name)
            if (name in missing || name in short) assertFalse(name, saved.exists())
            else assertTrue(name, saved.readBytes().contentEquals(world.files.getValue(path(name)).bytes))
        }
    }

    @Test
    fun theFoldersAreWalkedWhileTheirFilesDownload() {
        val world = World(slotCount = 2)
        world.folders[path("Season 2")] = listOf(folder("Extras"), file("e01.mkv", 100), file("e02.mkv", 200))
        world.folders[path("Season 2", "Extras")] = listOf(file("trailer.mp4", 50), folder("Empty"), folder("Locked"))
        world.folders[path("Season 2", "Extras", "Empty")] = emptyList()
        world.files[path("Season 2", "e01.mkv")] = Spec(bytes(100))
        world.files[path("Season 2", "e02.mkv")] = Spec(bytes(200))
        world.files[path("Season 2", "Extras", "trailer.mp4")] = Spec(bytes(50))
        world.files[path("notes.txt")] = Spec(bytes(5))
        val queue = queue(world)
        val id = queue.add(request(listOf(folder("Season 2"), file("notes.txt", 5))), "Movies")
        queue.awaitStage(id, DownloadStage.DONE)
        val done = queue.state(id)!!
        assertEquals(4, done.saved)
        assertEquals(listOf(DownloadFailure("Season 2/Extras/Locked", DownloadFailReason.UNLISTED)), done.failures)
        assertTrue(File(temp, "Season 2/Extras/trailer.mp4").isFile)
        assertTrue(File(temp, "Season 2/e02.mkv").isFile)
        // The structure is kept, an empty folder too.
        assertTrue(File(temp, "Season 2/Extras/Empty").isDirectory)
        assertTrue(File(temp, "notes.txt").isFile)
        assertFalse(done.counting)
    }

    @Test
    fun aWalkTheQuestionFinishedIsNotWalkedAgain() = runBlocking {
        val world = World(slotCount = 3)
        world.folders[path("Season 2")] = listOf(file("e01.mkv", 100), folder("Empty"))
        world.folders[path("Season 2", "Empty")] = emptyList()
        world.files[path("Season 2", "e01.mkv")] = Spec(bytes(100))
        val walked = DriveWalk.collect(parent, listOf("Season 2"), { at -> world.source(request(emptyList())).list(at) })
        val asked = world.listed.get()
        val queue = queue(world)
        val id = queue.add(request(listOf(folder("Season 2")), walked = walked), "Movies")
        queue.awaitStage(id, DownloadStage.DONE)
        assertEquals(asked, world.listed.get())
        assertEquals(1, queue.state(id)!!.saved)
        assertTrue(File(temp, "Season 2/Empty").isDirectory)
    }

    // --- names --------------------------------------------------------------------------------------

    /**
     * A folder that picks a free name from what it remembers the folder holds and creates it a
     * moment later, as [TreeSink] does: two claims at once would both find the same name free.
     */
    private class RacySink(private val disk: Disk) : DownloadSink {
        private val known = HashMap<String, MutableSet<String>>()

        override fun newFolder(parent: SinkNode?, name: String) = claim(parent, name, DownloadNames::freeFolder)
        override fun newFile(parent: SinkNode?, name: String) = claim(parent, name, DownloadNames::freeFile)

        private fun claim(parent: SinkNode?, name: String, free: (String, Set<String>) -> String): SinkNode {
            val dir = parent?.ref.orEmpty()
            val names = known.getOrPut(dir) { disk.list(dir).toHashSet() }
            val chosen = free(name, names)
            Thread.sleep(3)
            disk.create(dir, chosen)
            names += chosen
            return SinkNode("$dir/$chosen", chosen)
        }

        override fun write(file: SinkNode): OutputStream = ByteArrayOutputStream()
        override fun discard(file: SinkNode) = Unit
    }

    /** What the racy folder writes to: names by folder, and how many were created over another. */
    private class Disk {
        val names = ConcurrentHashMap<String, MutableSet<String>>()
        val clashes = AtomicInteger()

        fun list(dir: String): Set<String> = names[dir]?.let { synchronized(it) { it.toSet() } }.orEmpty()

        fun create(dir: String, name: String) {
            val here = names.getOrPut(dir) { HashSet() }
            synchronized(here) {
                if (here.any { it.equals(name, ignoreCase = true) }) clashes.incrementAndGet()
                here += name
            }
        }
    }

    @Test
    fun filesThatWantTheSameNameAtOnceNeverGetItBoth() {
        repeat(5) { round ->
            val world = World(slotCount = 4)
            val disk = Disk()
            world.sinkOf = { RacySink(disk) }
            val gate = CountDownLatch(1)
            val queue = queue(world)
            // A share on a case-sensitive server: three files this device's storage holds as one name.
            val names = listOf("x.bin", "X.bin", "x.BIN", "y.bin")
            val id = queue.add(request(share(world, names, gate = gate)), "Movies")
            await("four files open") { world.opened.size == 4 }
            gate.countDown()
            queue.awaitStage(id, DownloadStage.DONE)
            assertEquals("round $round", 0, disk.clashes.get())
            assertEquals(4, disk.list("").map { it.lowercase() }.toSet().size)
        }
    }

    @Test
    fun foldersMadeByFilesSideBySideAreMadeOnce() {
        val world = World(slotCount = 4)
        val disk = Disk()
        world.sinkOf = { RacySink(disk) }
        val gate = CountDownLatch(1)
        world.folders[path("Season 2")] = listOf("e1", "e2", "e3", "e4", "e5").map { file(it, 8) }
        for (n in 1..5) world.files[path("Season 2", "e$n")] = Spec(bytes(8, n), gate = gate)
        val queue = queue(world)
        val id = queue.add(request(listOf(folder("Season 2"))), "Movies")
        await("four files open") { world.opened.size == 4 }
        gate.countDown()
        queue.awaitStage(id, DownloadStage.DONE)
        assertEquals(setOf("Season 2"), disk.list(""))
        assertEquals(5, disk.list("/Season 2").size)
        assertEquals(0, disk.clashes.get())
    }

    @Test
    fun downloadsIntoOneFolderShareItsSinkWhileTheyRun() {
        val world = World(slotCount = 2)
        val disk = Disk()
        world.sinkOf = { RacySink(disk) }
        val gate = CountDownLatch(1)
        val queue = queue(world)
        // The same selection twice, at once: the second gets numbered names.
        val first = share(world, listOf("a", "b"), gate = gate)
        val a = queue.add(request(first), "Movies")
        val b = queue.add(request(first), "Movies")
        await("both of the first open") { world.opened.size == 2 }
        gate.countDown()
        queue.awaitStage(a, DownloadStage.DONE)
        queue.awaitStage(b, DownloadStage.DONE)
        assertEquals(0, disk.clashes.get())
        assertEquals(setOf("a", "b", "a (1)", "b (1)"), disk.list(""))
        // Once neither runs, a new download asks for the folder anew.
        val made = world.sinksMade.get()
        assertTrue("$made sinks for two downloads that overlapped", made <= 2)
        val c = queue.add(request(share(world, listOf("c"))), "Movies")
        queue.awaitStage(c, DownloadStage.DONE)
        assertEquals(made + 1, world.sinksMade.get())
    }

    @Test
    fun aDirectoryGivesEveryClaimAtOnceANameOfItsOwn() {
        val sink = DirSink(temp)
        val start = CountDownLatch(1)
        val claimed = Collections.synchronizedList(ArrayList<String>())
        val threads = (1..16).map { i ->
            Thread {
                start.await()
                claimed += if (i % 2 == 0) sink.newFile(null, "same.txt").name else sink.newFolder(null, "Same").name
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(16, claimed.map { it.lowercase() }.toSet().size)
        assertEquals(16, temp.list()!!.size)
    }

    // --- the node ---------------------------------------------------------------------------------

    @Test
    fun aNodeThatGoesStopsEveryDownload() {
        val world = World(slotCount = 1)
        world.running = false
        val queue = queue(world)
        world.files[path("a1")] = Spec(bytes(10), failOpen = { RangeIoException("HTTP 502", 502) })
        world.files[path("a2")] = Spec(bytes(10))
        val a = queue.add(request(listOf(file("a1", 10), file("a2", 10))), "Movies")
        val b = queue.add(request(share(world, listOf("b1"))), "Movies")
        queue.awaitStage(a, DownloadStage.STOPPED)
        queue.awaitStage(b, DownloadStage.STOPPED)
        settle()
        assertEquals(listOf(path("a1")), world.opened.toList())
        assertEquals(listOf(DownloadFailure("a1", DownloadFailReason.NETWORK)), queue.state(a)!!.failures)
        assertEquals(listOf(a), world.ended.map { it.id })
    }

    @Test
    fun aBrokenConnectionWhileTheNodeRunsIsOneFileMissing() {
        val world = World(slotCount = 1)
        val queue = queue(world)
        world.files[path("a1")] = Spec(bytes(10), failOpen = { RangeIoException("HTTP 502", 502) })
        world.files[path("a2")] = Spec(bytes(10))
        val a = queue.add(request(listOf(file("a1", 10), file("a2", 10))), "Movies")
        queue.awaitStage(a, DownloadStage.DONE)
        assertEquals(1, queue.state(a)!!.saved)
        assertEquals(listOf(DownloadFailure("a1", DownloadFailReason.NETWORK)), queue.state(a)!!.failures)
    }

    // --- what the page and the notification say ---------------------------------------------------

    @Test
    fun theSummaryAddsUpWhatRuns() {
        val folder = ReceiveFolder.Dir(temp)
        val running = DownloadState(
            1, "Movies", folder, false, DownloadStage.RUNNING, files = 10, bytes = 1_000, filesDone = 4, bytesDone = 400,
            writing = listOf(FileProgress("a", 10, 100), FileProgress("b")), bytesPerSecond = 300,
        )
        val counting = running.copy(id = 2, counting = true, files = 3, bytes = 300, filesDone = 1, bytesDone = 100, writing = listOf(FileProgress("c")), bytesPerSecond = null)
        val waiting = DownloadState(3, "Movies", folder, false)
        val over = running.copy(id = 4, stage = DownloadStage.DONE, writing = emptyList(), ended = 1)
        val summary = DownloadQueueSummary.of(listOf(over, running, counting, waiting))
        assertEquals(2, summary.running)
        assertEquals(1, summary.waiting)
        assertEquals(1, summary.finished)
        assertEquals(3, summary.active)
        assertEquals(3, summary.writing)
        assertEquals(13, summary.files)
        assertEquals(5, summary.filesDone)
        assertEquals(1_300L, summary.bytes)
        assertEquals(500L, summary.bytesDone)
        assertEquals(300L, summary.bytesPerSecond)
        // A download still counting leaves the whole unknown.
        assertNull(summary.fraction)
        assertEquals(0.4f, DownloadQueueSummary.of(listOf(running)).fraction)
        assertNull(DownloadQueueSummary.of(listOf(waiting)).bytesPerSecond)
    }

    @Test
    fun theSettingIsOneToFourAndThreeByDefault() {
        assertEquals(3, DownloadSlots.of(null))
        assertEquals(3, DownloadSlots.of(""))
        assertEquals(1, DownloadSlots.of("1"))
        assertEquals(4, DownloadSlots.of("4"))
        assertEquals(3, DownloadSlots.of("0"))
        assertEquals(3, DownloadSlots.of("9"))
        assertEquals(3, DownloadSlots.of("many"))
    }

    private companion object {
        const val PIECE = 7
        const val TIMEOUT_MS = 15_000L
    }
}
