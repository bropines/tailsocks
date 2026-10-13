package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

/*
 * The "What's new" dialog reads CHANGELOG.md, and CHANGELOG_RU.md for the Russian interface. The
 * first test is what keeps the second from rotting: a release that renames [Unreleased] in one
 * file and not in the other, or a bullet added to one, fails here instead of reaching the dialog
 * as English in a Russian screen (or as a stale translation).
 */
class ChangelogTest {
    private val repository: File = run {
        val module = listOf(File("."), File("app")).first { File(it, "src/main/res/values/strings.xml").isFile }
        module.canonicalFile.parentFile
    }
    private val english = Changelog.parse(File(repository, "CHANGELOG.md").readText())
    private val russian = Changelog.parse(File(repository, Changelog.ASSET_NAME_RU).readText())

    private fun Changelog.Section.isUnreleased() = version.equals("unreleased", ignoreCase = true)

    /** The sections must have the same groups in the same order, and as many bullets in each. */
    private fun assertSameShape(en: Changelog.Section, ru: Changelog.Section) {
        val label = "[${en.version}]"
        assertEquals("$label: the date", en.date, ru.date)
        assertEquals(
            "$label: the groups (Added, Changed, Fixed, ...) of CHANGELOG_RU.md",
            en.groups.size, ru.groups.size
        )
        en.groups.zip(ru.groups).forEach { (e, r) ->
            assertEquals("$label ${e.title} / ${r.title}: the bullets", e.items.size, r.items.size)
            assertEquals("$label ${e.title} / ${r.title}: the nesting of the bullets", e.items.map { it.level }, r.items.map { it.level })
        }
    }

    @Test
    fun theNewestVersionHasARussianCounterpart() {
        val newest = english.first { !it.isUnreleased() }
        val translated = russian.firstOrNull { it.version == newest.version }
        assertNotNull(
            "CHANGELOG_RU.md has no section [${newest.version}]: translate it, or rename [Unreleased] there as it was renamed in CHANGELOG.md",
            translated
        )
        assertSameShape(newest, translated!!)
    }

    @Test
    fun theUnreleasedWorkIsInBothFiles() {
        val coming = english.firstOrNull { it.isUnreleased() && it.groups.any { g -> g.items.isNotEmpty() } } ?: return
        val translated = russian.firstOrNull { it.isUnreleased() }
        assertNotNull("CHANGELOG_RU.md has no [Unreleased] section for the work CHANGELOG.md lists", translated)
        assertSameShape(coming, translated!!)
    }

    @Test
    fun everyRussianSectionHasAnEnglishOne() {
        val known = english.map { it.version }.toSet()
        assertEquals(emptyList<String>(), russian.map { it.version }.filter { it !in known })
    }

    @Test
    fun aRussianSectionIsShownInPlaceOfTheEnglishOneOfTheSameVersion() {
        val en = Changelog.parse(SAMPLE_EN)
        val ru = Changelog.parse(SAMPLE_RU)
        val shown = Changelog.pick(en, ru)
        assertEquals("1.1", shown?.version)
        assertEquals("Добавлено", shown?.groups?.single()?.title)
    }

    @Test
    fun aVersionWithoutRussianTextFallsBackToEnglish() {
        val en = Changelog.parse(SAMPLE_EN)
        // Only the older version is translated: the newest is shown as it is.
        val ru = Changelog.parse("## [1.0] - 2026-01-01\n\n### Добавлено\n\n- Старое.\n")
        assertSame(en.first { it.version == "1.1" }, Changelog.pick(en, ru))
        // A heading with nothing under it is no translation either.
        val empty = Changelog.parse("## [1.1] - 2026-02-01\n")
        assertSame(en.first { it.version == "1.1" }, Changelog.pick(en, empty))
        assertSame(en.first { it.version == "1.1" }, Changelog.pick(en, emptyList()))
    }

    @Test
    fun theUnreleasedSectionIsNeverTheOneShown() {
        val en = Changelog.parse("## [Unreleased]\n\n### Added\n\n- Soon.\n\n## [1.1] - 2026-02-01\n\n### Added\n\n- New.\n")
        val ru = Changelog.parse("## [Unreleased]\n\n### Добавлено\n\n- Скоро.\n")
        assertEquals("1.1", Changelog.pick(en, ru)?.version)
        assertEquals(null, Changelog.pick(emptyList(), ru))
    }

    // ─── A beta build ────────────────────────────────────────────────────────────────────
    // A beta has no section of its own: it goes out while its changes are still under
    // [Unreleased], and that is what its "What's new" shows, under the build's own name.

    @Test
    fun aBetaBuildShowsTheUnreleasedSectionUnderItsOwnName() {
        val en = Changelog.parse(SAMPLE_EN)
        val shown = Changelog.pick(en, emptyList(), versionName = "5.1.0-beta.2")
        assertEquals("5.1.0-beta.2", shown?.version)
        assertEquals("", shown?.date)
        assertEquals(listOf("Soon."), shown?.groups?.single()?.items?.map { it.text })
        // A debug build of the beta is the same beta.
        assertEquals("5.1.0-beta.2", Changelog.pick(en, emptyList(), versionName = "5.1.0-beta.2-dev")?.version)
    }

    @Test
    fun aBetaBuildShowsTheUnreleasedSectionInRussianToo() {
        val en = Changelog.parse(SAMPLE_EN)
        val ru = Changelog.parse("## [Unreleased]\n\n### Добавлено\n\n- Скоро.\n\n" + SAMPLE_RU)
        val shown = Changelog.pick(en, ru, versionName = "5.1.0-beta.2")
        assertEquals("5.1.0-beta.2", shown?.version)
        assertEquals("Добавлено", shown?.groups?.single()?.title)
        assertEquals(listOf("Скоро."), shown?.groups?.single()?.items?.map { it.text })
        // Not translated yet: the English list, still under the beta's name.
        val untranslated = Changelog.pick(en, Changelog.parse(SAMPLE_RU), versionName = "5.1.0-beta.2")
        assertEquals("5.1.0-beta.2", untranslated?.version)
        assertEquals("Added", untranslated?.groups?.single()?.title)
    }

    @Test
    fun aReleaseBuildNeverShowsTheUnreleasedSection() {
        val en = Changelog.parse(SAMPLE_EN)
        val ru = Changelog.parse("## [Unreleased]\n\n### Добавлено\n\n- Скоро.\n\n" + SAMPLE_RU)
        for (name in listOf("1.1", "5.1.0", "5.1.0-dev", "v2.0.0-beta", "v4.5.1-c76dbb.release", "")) {
            assertEquals(name, "1.1", Changelog.pick(en, emptyList(), versionName = name)?.version)
            assertEquals(name, "Новое.", Changelog.pick(en, ru, versionName = name)?.groups?.single()?.items?.single()?.text)
        }
    }

    @Test
    fun aBetaWithNothingUnreleasedShowsTheNewestRelease() {
        val en = Changelog.parse("## [Unreleased]\n\n## [1.1] - 2026-02-01\n\n### Added\n\n- New.\n")
        assertEquals("1.1", Changelog.pick(en, emptyList(), versionName = "5.1.0-beta.1")?.version)
        val none = Changelog.parse("## [1.1] - 2026-02-01\n\n### Added\n\n- New.\n")
        assertEquals("1.1", Changelog.pick(none, emptyList(), versionName = "5.1.0-beta.1")?.version)
    }

    /** The files themselves, as a beta cut from this commit would read them. */
    @Test
    fun aBetaOfThisCommitShowsItsUnreleasedWorkInBothLanguages() {
        val coming = english.firstOrNull { it.isUnreleased() && it.groups.any { g -> g.items.isNotEmpty() } } ?: return
        val inEnglish = Changelog.pick(english, emptyList(), versionName = "9.9.0-beta.1")!!
        assertEquals("9.9.0-beta.1", inEnglish.version)
        assertEquals(coming.groups, inEnglish.groups)
        val inRussian = Changelog.pick(english, russian, versionName = "9.9.0-beta.1")!!
        assertEquals("9.9.0-beta.1", inRussian.version)
        assertEquals(russian.first { it.isUnreleased() }.groups, inRussian.groups)
        assertSameShape(inEnglish, inRussian)
    }

    /** "What's new" appears once per version: once for each beta, and once more for the release. */
    @Test
    fun eachBetaAndTheReleaseAreSeenSeparately() {
        assertEquals("5.1.0-beta.1", Changelog.seenKey("5.1.0-beta.1"))
        assertEquals("5.1.0-beta.2", Changelog.seenKey("5.1.0-beta.2-dev"))
        assertEquals("5.1.0", Changelog.seenKey("5.1.0"))
        // What it always was for the names there have been, so no update re-shows the dialog.
        assertEquals("4.10.2", Changelog.seenKey("4.10.2"))
        assertEquals("4.10.2", Changelog.seenKey("4.10.2-dev"))
        assertEquals("3.6.0", Changelog.seenKey("v3.6.0-abc123.release"))
    }

    private companion object {
        const val SAMPLE_EN = """
## [Unreleased]

### Added

- Soon.

## [1.1] - 2026-02-01

### Added

- New.

## [1.0] - 2026-01-01

### Added

- Old.
"""
        const val SAMPLE_RU = """
## [1.1] - 2026-02-01

### Добавлено

- Новое.
"""
    }
}
