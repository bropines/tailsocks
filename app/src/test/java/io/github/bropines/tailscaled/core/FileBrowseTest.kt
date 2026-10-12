package io.github.bropines.tailscaled.core

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileBrowseTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String) = FileEntry(File("/x/$name"), isDirectory = true)
    private fun file(name: String) = FileEntry(File("/x/$name"), isDirectory = false, size = 1)
    private fun names(entries: List<FileEntry>) = entries.map { it.name }

    // The places a typical TV box or phone has: internal storage, Downloads, a USB stick, a folder
    // the caller asked for inside internal storage, and one in the app's private storage.
    private val primary = BrowseRoot(RootKind.PRIMARY, File("/storage/emulated/0"))
    private val downloads = BrowseRoot(RootKind.DOWNLOADS, File("/storage/emulated/0/Download"))
    private val usb = BrowseRoot(RootKind.VOLUME, File("/storage/1234-ABCD"), "USB drive", removable = true)
    private val startInside = BrowseRoot(RootKind.START, File("/storage/emulated/0/Download/Taildrop"))
    private val startPrivate = BrowseRoot(RootKind.START, File("/data/user/0/app/files/inbox"))
    private val roots = listOf(startInside, primary, downloads, usb)

    // --- order ---------------------------------------------------------------------------

    @Test
    fun foldersComeFirstThenNaturalOrderIgnoringCase() {
        val sorted = FileBrowse.arrange(
            listOf(file("b.txt"), dir("Zeta"), file("A.txt"), dir("alpha"), file("c.txt")), showHidden = false
        )
        assertEquals(listOf("alpha", "Zeta", "A.txt", "b.txt", "c.txt"), names(sorted))
    }

    @Test
    fun numbersAreComparedAsNumbers() {
        val sorted = FileBrowse.arrange(
            listOf(file("IMG_10.jpg"), file("IMG_9.jpg"), file("IMG_100.jpg"), file("IMG_1.jpg"), file("img_2.jpg")), false
        )
        assertEquals(listOf("IMG_1.jpg", "img_2.jpg", "IMG_9.jpg", "IMG_10.jpg", "IMG_100.jpg"), names(sorted))
        assertTrue(FileBrowse.naturalCompare("a 9", "a 10") < 0)
        assertTrue(FileBrowse.naturalCompare("v1.2.10", "v1.2.9") > 0)
        assertTrue(FileBrowse.naturalCompare("Серия 2", "Серия 10") < 0)
    }

    @Test
    fun aVeryLongRunOfDigitsDoesNotOverflow() {
        val big = "9".repeat(60)
        val bigger = "1" + "0".repeat(60)
        assertTrue(FileBrowse.naturalCompare("f$big", "f$bigger") < 0)
        assertTrue(FileBrowse.naturalCompare("f$bigger", "f$big") > 0)
        assertEquals(0, FileBrowse.naturalCompare("f$big", "F$big"))
    }

    @Test
    fun leadingZerosAndCaseTiesStillGiveOneOrder() {
        assertEquals(0, FileBrowse.naturalCompare("007", "7"))
        // Equal for the natural compare, told apart by their characters: always the same way round.
        val forward = FileBrowse.arrange(listOf(file("a"), file("A"), file("7"), file("007")), false)
        val backward = FileBrowse.arrange(listOf(file("007"), file("7"), file("A"), file("a")), false)
        assertEquals(names(forward), names(backward))
        assertEquals(listOf("007", "7", "A", "a"), names(forward))
    }

    @Test
    fun cyrillicSortsByLetterNotByCase() {
        val sorted = FileBrowse.arrange(listOf(file("вода"), file("Банан"), file("апельсин"), file("Яблоко")), false)
        assertEquals(listOf("апельсин", "Банан", "вода", "Яблоко"), names(sorted))
    }

    @Test
    fun aNamePrefixSortsBeforeItsExtension() {
        assertTrue(FileBrowse.naturalCompare("photo", "photo2") < 0)
        assertTrue(FileBrowse.naturalCompare("photo", "photo.jpg") < 0)
        assertEquals(0, FileBrowse.naturalCompare("", ""))
        assertTrue(FileBrowse.naturalCompare("", "a") < 0)
    }

    // --- hidden files ---------------------------------------------------------------------

    @Test
    fun hiddenEntriesAreLeftOutUnlessAsked() {
        val all = listOf(file(".nomedia"), dir(".cache"), file("..odd"), file("visible.txt"), dir("Docs"), file("a.b.c"))
        assertEquals(listOf("Docs", "a.b.c", "visible.txt"), names(FileBrowse.arrange(all, showHidden = false)))
        assertEquals(
            listOf(".cache", "Docs", "..odd", ".nomedia", "a.b.c", "visible.txt"),
            names(FileBrowse.arrange(all, showHidden = true))
        )
        assertTrue(FileBrowse.isHiddenName(".x"))
        assertFalse(FileBrowse.isHiddenName("x."))
        assertFalse(FileBrowse.isHiddenName("a.b"))
        assertFalse(FileBrowse.isHiddenName(""))
    }

    // --- paths ----------------------------------------------------------------------------

    @Test
    fun segmentsAreCleanedAndCompared() {
        assertEquals(listOf("storage", "emulated", "0"), FileBrowse.segments(File("/storage/emulated/0/")))
        assertEquals(listOf("a", "c"), FileBrowse.segments(File("/a/./b/../c")))
        assertEquals(emptyList<String>(), FileBrowse.segments(File("/")))
        assertEquals(listOf("a"), FileBrowse.segments(File("/../../a")))
        assertEquals(File("/storage/emulated/0/Download"), FileBrowse.clean(File("/storage//emulated/0/./Download/")))
        assertTrue(FileBrowse.same(File("/a/b/"), File("/a/b")))
    }

    @Test
    fun withinIsAboutWholeNamesNotStringPrefixes() {
        val download = File("/storage/emulated/0/Download")
        assertTrue(FileBrowse.isWithin(File("/storage/emulated/0/Download"), download))
        assertTrue(FileBrowse.isWithin(File("/storage/emulated/0/Download/a/b"), download))
        assertFalse(FileBrowse.isWithin(File("/storage/emulated/0/Download2"), download))
        assertFalse(FileBrowse.isWithin(File("/storage/emulated/0/Downloads"), download))
        assertFalse(FileBrowse.isWithin(File("/storage/emulated/0"), download))
        assertEquals(listOf("a", "b"), FileBrowse.relative(File("/storage/emulated/0/Download/a/b"), download))
        assertNull(FileBrowse.relative(File("/elsewhere"), download))
        assertEquals(emptyList<String>(), FileBrowse.relative(download, download))
    }

    @Test
    fun oddNamesSurviveParentsAndRelatives() {
        val odd = listOf(
            "My Files", "  leading and trailing  ", "Фото 2026 — отпуск", "日本語のフォルダ", "emoji 📁 dir",
            ".hidden dir", "a'b\"c", "100%", "semi;colon", "tab\there", "x".repeat(255), "...",
        )
        val root = File("/storage/1234-ABCD")
        for (name in odd) {
            val inner = File(File(root, name), "$name.txt")
            assertEquals(File(root, name), FileBrowse.parentOf(inner))
            assertEquals(listOf(name, "$name.txt"), FileBrowse.relative(inner, root))
            assertTrue(FileBrowse.isWithin(inner, File(root, name)))
            assertEquals(inner, FileBrowse.clean(inner))
        }
    }

    @Test
    fun parentOfTheTopIsSlashAndOfSlashNothing() {
        assertEquals(File("/"), FileBrowse.parentOf(File("/storage")))
        assertNull(FileBrowse.parentOf(File("/")))
    }

    // --- the Up rule ----------------------------------------------------------------------

    @Test
    fun upWalksToTheVolumeTopThenToThePlacesThenNowhere() {
        val deep = BrowseLocation.Dir(File("/storage/emulated/0/Download/Taildrop/2026/trip"))
        var at: BrowseLocation? = deep
        val seen = ArrayList<BrowseLocation?>()
        while (at != null) {
            seen.add(at)
            at = FileBrowse.up(at, roots)
        }
        assertEquals(
            listOf(
                "/storage/emulated/0/Download/Taildrop/2026/trip", "/storage/emulated/0/Download/Taildrop/2026",
                "/storage/emulated/0/Download/Taildrop", "/storage/emulated/0/Download", "/storage/emulated/0",
            ),
            seen.dropLast(1).map { (it as BrowseLocation.Dir).dir.path }
        )
        assertEquals(BrowseLocation.Roots, seen.last())
        assertNull(FileBrowse.up(BrowseLocation.Roots, roots))
    }

    @Test
    fun aRemovableVolumeTopGoesToThePlaces() {
        assertEquals(BrowseLocation.Roots, FileBrowse.up(BrowseLocation.Dir(File("/storage/1234-ABCD")), roots))
        assertEquals(
            BrowseLocation.Dir(File("/storage/1234-ABCD")),
            FileBrowse.up(BrowseLocation.Dir(File("/storage/1234-ABCD/Movies")), roots)
        )
    }

    @Test
    fun aStartFolderOutsideEveryVolumeIsItsOwnTop() {
        val withPrivate = listOf(startPrivate, primary, usb)
        assertTrue(FileBrowse.isTop(File("/data/user/0/app/files/inbox"), withPrivate))
        assertEquals(
            BrowseLocation.Roots,
            FileBrowse.up(BrowseLocation.Dir(File("/data/user/0/app/files/inbox")), withPrivate)
        )
        assertEquals(
            BrowseLocation.Dir(File("/data/user/0/app/files/inbox")),
            FileBrowse.up(BrowseLocation.Dir(File("/data/user/0/app/files/inbox/sub")), withPrivate)
        )
    }

    @Test
    fun aPathNoRootKnowsIsNeverClimbedOutOf() {
        val stray = File("/mnt/other/place")
        assertTrue(FileBrowse.isTop(stray, roots))
        assertEquals(BrowseLocation.Roots, FileBrowse.up(BrowseLocation.Dir(stray), roots))
    }

    @Test
    fun lookAlikeNamesAreNotTheSameVolume() {
        // "/storage/emulated/01" is not inside "/storage/emulated/0".
        val other = File("/storage/emulated/01/x")
        assertEquals(other, FileBrowse.top(other, roots))
    }

    // --- where it opens --------------------------------------------------------------------

    @Test
    fun itOpensAtTheStartOrTheNearestFolderThatStillExists() {
        val existing = setOf("/storage/emulated/0", "/storage/emulated/0/Download", "/storage/emulated/0/Download/Taildrop")
        val isDir: (File) -> Boolean = { it.path in existing }
        assertEquals(
            File("/storage/emulated/0/Download/Taildrop"),
            FileBrowse.initialDir(File("/storage/emulated/0/Download/Taildrop/"), roots, isDir)
        )
        // Deleted meanwhile: the folder above that is still there.
        assertEquals(
            File("/storage/emulated/0/Download/Taildrop"),
            FileBrowse.initialDir(File("/storage/emulated/0/Download/Taildrop/gone/deeper"), roots, isDir)
        )
        assertNull(FileBrowse.initialDir(null, roots, isDir))
        assertNull(FileBrowse.initialDir(File("/somewhere/else"), roots, isDir))
        // The stick was pulled out: nothing of the start is left within the places.
        assertNull(FileBrowse.initialDir(File("/storage/1234-ABCD/Movies"), roots, isDir))
    }

    // --- the path line ---------------------------------------------------------------------

    @Test
    fun theBreadcrumbIsThePlaceAndTheNamesUnderIt() {
        val crumb = FileBrowse.breadcrumb(File("/storage/emulated/0/Download/Мои файлы/a b"), roots)
        assertEquals(RootKind.PRIMARY, crumb.root?.kind)
        assertEquals(listOf("Download", "Мои файлы", "a b"), crumb.trail)

        val onStick = FileBrowse.breadcrumb(File("/storage/1234-ABCD"), roots)
        assertEquals(usb, onStick.root)
        assertEquals(emptyList<String>(), onStick.trail)

        val inPrivate = FileBrowse.breadcrumb(File("/data/user/0/app/files/inbox/x"), listOf(startPrivate, primary))
        assertEquals(RootKind.START, inPrivate.root?.kind)
        assertEquals(listOf("x"), inPrivate.trail)

        val unknown = FileBrowse.breadcrumb(File("/mnt/other/place"), roots)
        assertNull(unknown.root)
        assertEquals(listOf("mnt", "other", "place"), unknown.trail)
    }

    // --- roots -----------------------------------------------------------------------------

    private fun vol(path: String?, primary: Boolean = false, removable: Boolean = true, mounted: Boolean = true, name: String? = "USB drive") =
        VolumeInfo(path?.let(::File), primary, removable, mounted, name)

    @Test
    fun rootsAreStartPrimaryDownloadsThenVolumes() {
        val all = FileBrowse.assembleRoots(
            primary = File("/storage/emulated/0"),
            downloads = File("/storage/emulated/0/Download"),
            volumes = listOf(vol("/storage/emulated/0", primary = true, removable = false, name = "Internal"), vol("/storage/1234-ABCD", name = "SanDisk")),
            appDirs = listOf(File("/storage/emulated/0/Android/data/p/files"), File("/storage/1234-ABCD/Android/data/p/files")),
            start = File("/storage/emulated/0/Documents/Taildrop"),
            isDirectory = { true },
        )
        assertEquals(
            listOf(RootKind.START, RootKind.PRIMARY, RootKind.DOWNLOADS, RootKind.VOLUME),
            all.map { it.kind }
        )
        assertEquals("SanDisk", all.last().label)
        // The same stick seen twice (volume list and app directory) is one place.
        assertEquals(4, all.size)
    }

    @Test
    fun aStartFolderThatIsAnotherRootIsNotListedTwice() {
        val all = FileBrowse.assembleRoots(
            File("/storage/emulated/0"), File("/storage/emulated/0/Download"), emptyList(), emptyList(),
            start = File("/storage/emulated/0/Download/"), isDirectory = { true },
        )
        assertEquals(listOf(RootKind.PRIMARY, RootKind.DOWNLOADS), all.map { it.kind })
    }

    @Test
    fun missingUnmountedAndNamelessVolumesAreHandled() {
        val present = setOf("/storage/emulated/0", "/storage/AAAA-0001", "/storage/BBBB-0002")
        val all = FileBrowse.assembleRoots(
            primary = File("/storage/emulated/0"),
            downloads = File("/storage/emulated/0/Download"), // not on the disk
            volumes = listOf(
                vol("/storage/AAAA-0001", name = "  "),
                vol("/storage/CCCC-0003", mounted = false),
                vol(null),
                vol("/storage/DDDD-0004"), // listed, but not a folder now
            ),
            // A volume the platform gave no path for, found through the app directory.
            appDirs = listOf(null, File("/storage/BBBB-0002/Android/data/p/files"), File("/odd/place")),
            start = File("/storage/emulated/0/gone"),
            isDirectory = { it.path in present },
        )
        assertEquals(listOf(RootKind.PRIMARY, RootKind.VOLUME, RootKind.VOLUME), all.map { it.kind })
        assertNull(all[1].label)
        assertEquals(File("/storage/BBBB-0002"), all[2].dir)
    }

    @Test
    fun theVolumeRootComesFromAnAppSpecificDirectory() {
        assertEquals(File("/storage/1234-ABCD"), FileBrowse.volumeRootOfAppDir(File("/storage/1234-ABCD/Android/data/io.pkg/files")))
        assertEquals(File("/storage/emulated/0"), FileBrowse.volumeRootOfAppDir(File("/storage/emulated/0/Android/media/io.pkg")))
        assertNull(FileBrowse.volumeRootOfAppDir(File("/storage/1234-ABCD/Movies")))
        assertNull(FileBrowse.volumeRootOfAppDir(File("/Android/data/x")))
        assertNull(FileBrowse.volumeRootOfAppDir(File("/storage/x/Android/other/x")))
        assertNull(FileBrowse.volumeRootOfAppDir(File("/storage/emulated/0/Download/Android/data/x")))
    }

    // --- access ----------------------------------------------------------------------------

    @Test
    fun theGrantThatIsMissingDependsOnTheAndroidVersion() {
        for (sdk in listOf(24, 26, 28, 29)) {
            assertEquals(StorageAccess.NEEDS_PERMISSION, FileBrowse.storageAccessFor(sdk, storagePermission = false, allFilesAccess = false))
            assertEquals(StorageAccess.GRANTED, FileBrowse.storageAccessFor(sdk, storagePermission = true, allFilesAccess = false))
            // All files access means nothing before 11.
            assertEquals(StorageAccess.NEEDS_PERMISSION, FileBrowse.storageAccessFor(sdk, false, true))
        }
        for (sdk in listOf(Build.VERSION_CODES.R, 31, 33, 34, 36)) {
            assertEquals(StorageAccess.NEEDS_ALL_FILES, FileBrowse.storageAccessFor(sdk, storagePermission = true, allFilesAccess = false))
            assertEquals(StorageAccess.GRANTED, FileBrowse.storageAccessFor(sdk, storagePermission = false, allFilesAccess = true))
        }
    }

    @Test
    fun theAppsOwnFoldersNeedNoGrant() {
        val pkg = "io.github.bropines.tailscaled"
        assertTrue(FileBrowse.isAppOwnedExternal(File("/storage/emulated/0/Android/data/$pkg"), pkg))
        assertTrue(FileBrowse.isAppOwnedExternal(File("/storage/1234-ABCD/Android/data/$pkg/files/Taildrop"), pkg))
        assertTrue(FileBrowse.isAppOwnedExternal(File("/storage/emulated/0/Android/media/$pkg/x"), pkg))
        assertTrue(FileBrowse.isAppOwnedExternal(File("/storage/emulated/0/Android/obb/$pkg"), pkg))
        assertFalse(FileBrowse.isAppOwnedExternal(File("/storage/emulated/0/Android/data"), pkg))
        assertFalse(FileBrowse.isAppOwnedExternal(File("/storage/emulated/0/Android/data/$pkg.dev"), pkg))
        assertFalse(FileBrowse.isAppOwnedExternal(File("/storage/emulated/0/Android/data/other.app"), pkg))
        // An "Android" folder somewhere inside a volume is just a folder.
        assertFalse(FileBrowse.isAppOwnedExternal(File("/storage/emulated/0/Download/Android/data/$pkg"), pkg))
        assertFalse(FileBrowse.isAppOwnedExternal(File("/data/user/0/$pkg/files"), pkg))
    }

    @Test
    fun onlySharedStorageIsWhereTheMissingAccessMatters() {
        assertTrue(FileBrowse.isSharedStoragePath(File("/storage/emulated/0/DCIM")))
        assertTrue(FileBrowse.isSharedStoragePath(File("/storage/1234-ABCD")))
        assertTrue(FileBrowse.isSharedStoragePath(File("/sdcard/Download")))
        assertFalse(FileBrowse.isSharedStoragePath(File("/data/user/0/io.pkg/files/taildrop")))
        assertFalse(FileBrowse.isSharedStoragePath(File("/")))
    }

    @Test
    fun theAdbLineNamesThePackage() {
        assertEquals(
            "adb shell appops set io.github.bropines.tailscaled.dev MANAGE_EXTERNAL_STORAGE allow",
            FileBrowse.adbGrantCommand("io.github.bropines.tailscaled.dev")
        )
    }

    // --- a real folder ----------------------------------------------------------------------

    @Test
    fun listingARealFolderGivesFoldersFirstAndNoHiddenFiles() {
        val root = tmp.newFolder("root")
        File(root, "b 10.txt").writeText("0123456789")
        File(root, "b 9.txt").writeText("")
        File(root, ".secret").writeText("x")
        File(root, "Фото").mkdir()
        File(root, ".cache").mkdir()
        File(root, "a.dir").mkdir()

        val shown = FileBrowse.listDirectory(root, showHidden = false) as Listing.Entries
        assertEquals(listOf("a.dir", "Фото", "b 9.txt", "b 10.txt"), names(shown.entries))
        assertFalse(shown.limited)
        assertTrue(shown.writable)
        val ten = shown.entries.last()
        assertEquals(10L, ten.size)
        assertTrue(ten.modified > 0)
        assertEquals(0L, shown.entries.first().size)
        assertTrue(shown.entries.first().isDirectory)

        val all = FileBrowse.listDirectory(root, showHidden = true) as Listing.Entries
        assertEquals(listOf(".cache", "a.dir", "Фото", ".secret", "b 9.txt", "b 10.txt"), names(all.entries))
    }

    @Test
    fun anEmptyFolderIsEmptyNotAnError() {
        val listing = FileBrowse.listDirectory(tmp.newFolder("empty"), showHidden = false)
        assertTrue(listing is Listing.Entries && listing.entries.isEmpty())
    }

    @Test
    fun aFolderThatIsGoneOrIsAFileIsNotFoundAndLimitedMeansAskForAccess() {
        val gone = File(tmp.root, "no such folder")
        assertEquals(Listing.Unreadable(gone, Listing.Reason.NOT_FOUND), FileBrowse.listDirectory(gone, false))
        val plain = tmp.newFile("plain.txt")
        assertEquals(Listing.Unreadable(plain, Listing.Reason.NOT_FOUND), FileBrowse.listDirectory(plain, false))
        assertEquals(Listing.Unreadable(gone, Listing.Reason.NO_ACCESS), FileBrowse.listDirectory(gone, false, limited = true))
    }

    @Test
    fun aFolderTheSystemRefusesIsUnreadableNotAnException() {
        val locked = tmp.newFolder("locked")
        val denied = locked.setReadable(false) && locked.setExecutable(false)
        // As root (some CI containers) the mode bits are ignored and there is nothing to refuse.
        assumeTrue(denied && locked.listFiles() == null)
        try {
            assertEquals(Listing.Unreadable(locked, Listing.Reason.DENIED), FileBrowse.listDirectory(locked, false))
            assertEquals(Listing.Unreadable(locked, Listing.Reason.NO_ACCESS), FileBrowse.listDirectory(locked, false, limited = true))
        } finally {
            locked.setReadable(true)
            locked.setExecutable(true)
        }
    }

    @Test
    fun aRealFolderWithOddNamesLists() {
        val root = tmp.newFolder("odd")
        val longName = "n".repeat(200) + ".bin"
        val names = listOf("with space.txt", "Привет, мир.md", ".dotfile", "q'uote\"d.txt", "emoji 😀.png", longName)
        for (n in names) File(root, n).writeText("x")
        val shown = FileBrowse.listDirectory(root, showHidden = true) as Listing.Entries
        assertEquals(names.size, shown.entries.size)
        assertTrue(longName in names(shown.entries))
        assertEquals(File(root, longName), shown.entries.first { it.name == longName }.file)
    }
}
