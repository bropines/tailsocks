package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** A path typed or pasted into the share browser's field, and the looking for it level by level. */
class TaildrivePathTest {

    private val tailnet = "tail4a2c9.ts.net"

    private fun parse(typed: String) = DrivePathInput.parse(typed, tailnet)

    @Test
    fun theFieldShowsTheNamesUnderTheTailnet() {
        assertEquals("nas/Movies/Sci-Fi", DrivePathInput.format(DriveLocation(listOf(tailnet, "nas", "Movies", "Sci-Fi"))))
        assertEquals("", DrivePathInput.format(DriveLocation(listOf(tailnet))))
    }

    @Test
    fun slashesAndSpacesCountForNothing() {
        val wanted = listOf("nas", "Movies", "Sci-Fi")
        assertEquals(wanted, parse("nas/Movies/Sci-Fi"))
        assertEquals(wanted, parse("nas/Movies/Sci-Fi/"))
        assertEquals(wanted, parse("/nas//Movies///Sci-Fi"))
        assertEquals(wanted, parse("  nas / Movies /Sci-Fi  "))
        assertEquals(listOf("nas", "Season 2"), parse("nas/Season 2"))
        assertEquals(emptyList<String>(), parse(" / "))
    }

    @Test
    fun aPastedPathLosesWhatIsNotANameUnderTheTailnet() {
        val wanted = listOf("nas", "Movies", "Sci Fi")
        assertEquals(wanted, parse("/$tailnet/nas/Movies/Sci Fi"))
        assertEquals(wanted, parse("TAIL4A2C9.TS.NET/nas/Movies/Sci Fi"))
        assertEquals(wanted, parse("http://100.100.100.100:8080/$tailnet/nas/Movies/Sci%20Fi"))
        assertEquals(wanted, parse("http://127.0.0.1:33445/$tailnet/nas/Movies/Sci%20Fi/"))
        assertEquals(wanted, parse("""\\100.100.100.100\DavWWWRoot\$tailnet\nas\Movies\Sci Fi"""))
        assertEquals(wanted, parse("100.100.100.100:8080/$tailnet/nas/Movies/Sci Fi"))
        // A plus in an address is a plus, and outside one a percent is a percent.
        assertEquals(listOf("nas", "C++"), parse("http://100.100.100.100:8080/$tailnet/nas/C++"))
        assertEquals(listOf("nas", "100%25"), parse("nas/100%25"))
    }

    // --- looking for it --------------------------------------------------------------------------

    private val base = DriveLocation(listOf(tailnet))

    private val tree = mapOf(
        "/$tailnet" to listOf(DriveEntry("HomeLab-NAS", DriveKind.DEVICE), DriveEntry("laptop", DriveKind.DEVICE)),
        "/$tailnet/HomeLab-NAS" to listOf(DriveEntry("movies", DriveKind.SHARE)),
        "/$tailnet/HomeLab-NAS/movies" to listOf(
            DriveEntry("Sci-Fi", DriveKind.FOLDER),
            DriveEntry("sci-fi old", DriveKind.FOLDER),
            DriveEntry("Dune.mkv", DriveKind.VIDEO, 10)
        ),
        "/$tailnet/HomeLab-NAS/movies/Sci-Fi" to emptyList(),
    )

    private fun list(at: DriveLocation): DriveResult<List<DriveEntry>> =
        tree[at.davPath]?.let { DriveResult.Ok(it) } ?: DriveResult.Err(DriveError.Unreachable)

    private fun find(typed: String) = DrivePathInput.descend(base, parse(typed), ::list)

    @Test
    fun aDeviceTypedInAnotherCaseIsTheDevice() {
        assertEquals(PathFound.Folder(DriveLocation(listOf(tailnet, "HomeLab-NAS", "movies", "Sci-Fi"))), find("homelab-nas/Movies/sci-fi/"))
    }

    @Test
    fun anExactNameWinsOverOneThatDiffersInCase() {
        assertEquals(DriveLocation(listOf(tailnet, "HomeLab-NAS", "movies", "Sci-Fi")), (find("HomeLab-NAS/movies/Sci-Fi") as PathFound.Folder).location)
    }

    @Test
    fun aFileIsFoundInItsFolder() {
        assertEquals(PathFound.File(DriveLocation(listOf(tailnet, "HomeLab-NAS", "movies")), "Dune.mkv"), find("homelab-nas/movies/dune.MKV"))
    }

    @Test
    fun whatIsNotThereSaysWhereThePathStops() {
        assertEquals(PathFound.Missing(DriveLocation(listOf(tailnet, "HomeLab-NAS", "movies")), "Horror"), find("homelab-nas/movies/Horror/1980s"))
        assertEquals(PathFound.Missing(base, "desktop"), find("desktop/movies"))
        // Past a file there is nothing.
        assertEquals(PathFound.Missing(DriveLocation(listOf(tailnet, "HomeLab-NAS", "movies")), "extra"), find("homelab-nas/movies/Dune.mkv/extra"))
    }

    @Test
    fun aListingThatFailsSaysWhere() {
        assertEquals(PathFound.Failed(DriveLocation(listOf(tailnet, "laptop")), DriveError.Unreachable), find("laptop/docs"))
    }

    @Test
    fun nothingTypedIsTheListOfDevices() {
        assertEquals(PathFound.Folder(base), find(""))
    }
}
