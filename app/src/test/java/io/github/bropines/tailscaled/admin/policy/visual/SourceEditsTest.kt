package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.policy.HuArray
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.HuObject
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.assertMeaning
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.insertAt
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.json
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.parsed
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.removeAt
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.sample
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.setAt
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SourceEditsTest {

    private fun p(vararg s: Any) = PolicyPath.of(*s)

    // ---- the tree ----

    @Test
    fun theTreeReadsTheSamePolicyAsHuJson() {
        val t = SourceTree.parse(sample)
        val hu = HuJson.parse(sample)
        assertEquals(hu.fields.map { it.name }, t.root.members.map { it.key })
        // Every value's span is exactly its source: re-reading a span gives the same meaning.
        fun check(v: SrcValue, h: io.github.bropines.tailscaled.admin.policy.HuNode) {
            assertEquals(HuJson.canonical(h), HuJson.canonical(json(t.slice(v))))
            when (v) {
                is SrcObject -> v.members.forEach { m -> check(m.value, (h as HuObject)[m.key!!]!!) }
                is SrcArray -> v.members.forEachIndexed { i, m -> check(m.value, (h as HuArray).items[i]) }
                else -> Unit
            }
        }
        check(t.root, hu)
        assertEquals("accept", (t.at(p("acls", 0, "action")) as SrcString).value)
        assertNull(t.at(p("acls", 99)))
        assertNull(t.at(p("hosts")))
    }

    @Test
    fun aNoOpEditGivesBackTheSameBytes() {
        assertEquals(sample, SourceEdits.applyEdits(sample, emptyList()))
        // Writing back what is already there changes nothing either.
        assertEquals(sample, SourceEdits.set(sample, p("ssh", 0, "action"), "accept".pv()))
        val dst = (SourceTree.parse(sample).at(p("acls", 0, "dst")) as SrcArray).members.map { (it.value as SrcString).value }
        assertEquals(sample, SourceEdits.setStrings(sample, p("acls", 0, "dst"), dst))
        assertEquals(sample, SourceEdits.ensureSection(sample, "acls", PolicyValue.Arr(emptyList())))
    }

    @Test
    fun commentsAreReadAsNotesHeadersAndTrailers() {
        val t = SourceTree.parse(sample)
        val acls = t.root["acls"] as SrcArray
        fun c(i: Int) = t.commentsOf(acls, i)
        // Opening a list of several rules: a heading for the run, not the first rule's own note.
        assertTrue(c(0).header!!.startsWith("--- 0. ПРАВИЛА ДЛЯ АДМИНИСТРАТОРОВ"))
        assertTrue(c(0).header!!.contains("Явный список"))
        assertNull(c(0).note)
        // After a blank line: a header, which heads a run of rules.
        assertEquals("--- 2. DNS ПРАВИЛА ---", c(6).header)
        assertNull(c(6).note)
        assertNull(c(7).note)
        // Glued to the rule before it and to this one: this rule's note.
        assertEquals("--- 4.1 ACCESS FOR EXTERNAL USER ---", c(12).note)
        assertTrue(c(11).note!!.lines().size == 2)
        // Top-level sections: the comment over one is its note, blank line above or not.
        val root = t.root
        assertTrue(t.commentsOf(root, root.indexOf("tests")).note!!.startsWith("Сервер не примет"))
        assertTrue(t.commentsOf(root, root.indexOf("derpMap")).note!!.startsWith("Отключение"))
        assertTrue(t.commentsOf(root, root.indexOf("groups")).note!!.startsWith("--- 1. ОПРЕДЕЛЕНИЕ ГРУПП"))
        // A list's opening comment is a note when the member is alone in it (the default policy's "Allow all").
        val alone = SourceTree.parse("{\"acls\": [\n\t// Allow all connections.\n\t{\"action\": \"accept\", \"src\": [\"*\"], \"dst\": [\"*:*\"]},\n]}")
        assertEquals("Allow all connections.", alone.commentsOf(alone.root["acls"] as SrcArray, 0).note)
        val trailing = SourceTree.parse("{\n\t\"a\": [\"x\"], // why\n\t\"b\": 1,\n}")
        assertEquals("why", trailing.commentsOf(trailing.root, 0).trailing)
        assertNull(trailing.commentsOf(trailing.root, 1).trailing)
    }

    // ---- set ----

    @Test
    fun setAScalarChangesOnlyItsCharacters() {
        val after = SourceEdits.set(sample, p("ssh", 0, "action"), "check".pv())
        val w = window(sample, after)
        assertEquals("accept", w.removed)
        assertEquals("check", w.inserted)
        assertEquals(sample.indexOf("\"accept\"", sample.indexOf("\"ssh\"")) + 1, w.at)
        assertMeaning(setAt(parsed(sample), listOf("ssh", 0, "action"), json("\"check\"")), after)
    }

    @Test
    fun setWritesNonAsciiAndEscapes() {
        val after = SourceEdits.set(sample, p("tests", 0, "src"), "гость \"q\"@example.com".pv())
        assertTrue(after.contains("\"гость \\\"q\\\"@example.com\""))
        assertMeaning(setAt(parsed(sample), listOf("tests", 0, "src"), json("\"гость \\\"q\\\"@example.com\"")), after)
    }

    // ---- insert ----

    @Test
    fun insertIntoAOneLineList() {
        val after = SourceEdits.append(sample, p("acls", 2, "dst"), "tag:dns:53".pv())
        assertEquals("", window(sample, after).removed)
        assertEquals(", \"tag:dns:53\"", window(sample, after).inserted)
        assertTrue(after.contains("\"dst\":    [\"tag:master:*\", \"tag:dns:53\"],"))
        assertMeaning(insertAt(parsed(sample), listOf("acls", 2, "dst"), 1, json("\"tag:dns:53\"")), after)

        val first = SourceEdits.insert(sample, p("acls", 2, "dst"), 0, "tag:dns:53".pv())
        assertTrue(first.contains("\"dst\":    [\"tag:dns:53\", \"tag:master:*\"],"))
    }

    @Test
    fun insertIntoAMultiLineListCopiesTheIndentAndTheTrailingComma() {
        val after = SourceEdits.append(sample, p("acls", 0, "dst"), "tag:new:22".pv())
        assertEquals("", window(sample, after).removed)
        assertTrue(after.contains("\t\t\t\t\"autogroup:internet:*\",\n\t\t\t\t\"tag:new:22\",\n\t\t\t],"))
        assertMeaning(insertAt(parsed(sample), listOf("acls", 0, "dst"), 14, json("\"tag:new:22\"")), after)

        val middle = SourceEdits.insert(sample, p("acls", 0, "dst"), 1, "tag:new:22".pv())
        assertTrue(middle.contains("\"group:prod-admin:*\",\n\t\t\t\t\"tag:new:22\",\n\t\t\t\t\"group:dev-admin:*\","))
    }

    @Test
    fun aNewRuleLooksLikeItsNeighbours() {
        val rule = PolicyValue.obj(
            "action" to "accept".pv(),
            "src" to listOf("group:guest-users").pv(),
            "dst" to listOf("tag:dns:53").pv(),
        )
        val after = SourceEdits.append(sample, p("acls"), rule)
        val expected = "\t\t{\n" +
            "\t\t\t\"action\": \"accept\",\n" +
            "\t\t\t\"src\":    [\"group:guest-users\"],\n" +
            "\t\t\t\"dst\":    [\"tag:dns:53\"],\n" +
            "\t\t},\n"
        assertEquals("", window(sample, after).removed)
        assertTrue(after.contains("\"dst\":    [\"tag:android:*\"],\n\t\t},\n$expected\t],"))
        assertMeaning(insertAt(parsed(sample), listOf("acls"), 14, json("{\"action\":\"accept\",\"src\":[\"group:guest-users\"],\"dst\":[\"tag:dns:53\"]}")), after)
    }

    @Test
    fun aLongListInANewRuleGoesOneItemPerLine() {
        val many = (1..12).map { "tag:service-number-$it:443" }
        val after = SourceEdits.append(sample, p("acls"), PolicyValue.obj("action" to "accept".pv(), "src" to listOf("*").pv(), "dst" to many.pv()))
        val ins = after
        assertTrue(ins, ins.contains("\t\t\t\"dst\": [\n\t\t\t\t\"tag:service-number-1:443\",\n"))
        assertTrue(ins.contains("\t\t\t\"src\":    [\"*\"],"))
    }

    @Test
    fun putAddsAFieldInRuleOrderAlignedWithTheOthers() {
        val after = SourceEdits.put(sample, p("acls", 2), "proto", "tcp".pv())
        assertEquals("", window(sample, after).removed)
        assertTrue(after.contains("\"src\":    [\"tag:master\"],\n\t\t\t\"proto\":  \"tcp\",\n\t\t\t\"dst\":    [\"tag:master:*\"],"))
        assertMeaning(setAt(parsed(sample), listOf("acls", 2, "proto"), json("\"tcp\"")), after)
    }

    // ---- remove ----

    @Test
    fun removeARuleTakesItsNoteAndLeavesTheHeaderOfTheNext() {
        val withNote = SourceEdits.remove(sample, p("acls", 12))
        assertFalse(withNote.contains("4.1 ACCESS FOR EXTERNAL USER"))
        assertFalse(withNote.contains("carol@example.com"))
        assertTrue(withNote.contains("// --- 4.2 ГОСТИ-ПОЛЬЗОВАТЕЛИ"))
        assertMeaning(removeAt(parsed(sample), listOf("acls", 12)), withNote)

        // The first DNS rule has a header, not a note: the header stays and now heads the second.
        val dns = SourceEdits.remove(sample, p("acls", 6))
        assertTrue(dns.contains("\t\t// --- 2. DNS ПРАВИЛА ---\n\t\t{\n\t\t\t\"action\": \"accept\",\n\t\t\t\"src\":    [\"group:prod-admin\", \"group:dev-admin\"],"))
        assertMeaning(removeAt(parsed(sample), listOf("acls", 6)), dns)

        val keepNote = SourceEdits.remove(sample, p("acls", 12), withNote = false)
        assertTrue(keepNote.contains("4.1 ACCESS FOR EXTERNAL USER"))
    }

    @Test
    fun removeASectionTakesItsCommentAndAMemberLeavesTheListsHeading() {
        val noTests = SourceEdits.remove(sample, p("tests"))
        assertFalse(noTests.contains("Сервер не примет"))
        assertTrue(noTests.endsWith("\t],\n}\n"))
        assertMeaning(removeAt(parsed(sample), listOf("tests")), noTests)

        val noMaster = SourceEdits.remove(sample, p("tagOwners", "tag:master"))
        assertTrue(noMaster.contains("\t\"tagOwners\": {\n\t\t// PRODUCTION\n\t\t\"tag:server\": [\"group:prod-admin\"],\n"))

        val noDerp = SourceEdits.remove(sample, p("derpMap"))
        assertTrue(noDerp.startsWith("{\n\t// --- 1. ОПРЕДЕЛЕНИЕ ГРУПП"))
    }

    @Test
    fun removeEveryRuleOneByOneKeepsEverythingElse() {
        val acls = (SourceTree.parse(sample).root["acls"] as SrcArray).members.size
        for (i in 0 until acls) {
            val after = SourceEdits.remove(sample, p("acls", i))
            assertMeaning(removeAt(parsed(sample), listOf("acls", i)), after)
            val w = window(sample, after)
            assertEquals("a removal only removes", "", w.inserted.trim())
            assertFalse("no double blank line left at $i", after.contains("\n\n\n"))
        }
    }

    @Test
    fun removeItemsFromOneLineLists() {
        val text = "{\"a\": [\"x\", \"y\", \"z\"], \"b\": [\"x\", \"y\",], \"c\": [\"only\"]}"
        assertEquals("{\"a\": [\"y\", \"z\"], \"b\": [\"x\", \"y\",], \"c\": [\"only\"]}", SourceEdits.remove(text, p("a", 0)))
        assertEquals("{\"a\": [\"x\", \"z\"], \"b\": [\"x\", \"y\",], \"c\": [\"only\"]}", SourceEdits.remove(text, p("a", 1)))
        assertEquals("{\"a\": [\"x\", \"y\"], \"b\": [\"x\", \"y\",], \"c\": [\"only\"]}", SourceEdits.remove(text, p("a", 2)))
        assertEquals("{\"a\": [\"x\", \"y\", \"z\"], \"b\": [\"x\",], \"c\": [\"only\"]}", SourceEdits.remove(text, p("b", 1)))
        assertEquals("{\"a\": [\"x\", \"y\", \"z\"], \"b\": [\"x\", \"y\",], \"c\": []}", SourceEdits.remove(text, p("c", 0)))
        assertEquals("{\"a\": [\"x\", \"y\", \"z\"], \"b\": [\"x\", \"y\",]}", SourceEdits.remove(text, p("c")))
    }

    @Test
    fun removingTheLastItemOfASectionLeavesItEmptyNotBroken() {
        val one = SourceEdits.remove(sample, p("grants", 0))
        assertTrue(one.contains("\t\"grants\": [],\n"))
        assertMeaning(setAt(parsed(sample), listOf("grants"), json("[]")), one)
        // A comment inside stays, and so does the list's own layout around it.
        val text = "{\n\t\"tests\": [\n\t\t// keep me\n\t\t{\"src\": \"a@b\", \"accept\": [\"tag:x:22\"]},\n\t],\n}\n"
        val after = SourceEdits.remove(text, p("tests", 0), withNote = false)
        assertEquals("{\n\t\"tests\": [\n\t\t// keep me\n\t],\n}\n", after)
    }

    // ---- JSON style: no trailing commas, spaces ----

    private val plainJson = """
        {
          "groups": {
            "group:a": ["x@example.com"]
          },
          "acls": [
            {
              "action": "accept",
              "src": ["group:a"],
              "dst": ["*:*"]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun aJsonFileStaysJson() {
        val style = SourceStyle.of(SourceTree.parse(plainJson))
        assertEquals("  ", style.indentUnit)
        assertFalse(style.trailingCommas)
        assertFalse(style.alignValues)

        val added = SourceEdits.append(plainJson, p("acls"), PolicyValue.obj("action" to "accept".pv(), "src" to listOf("x@example.com").pv(), "dst" to listOf("tag:y:22").pv()))
        val expected = """
            {
              "groups": {
                "group:a": ["x@example.com"]
              },
              "acls": [
                {
                  "action": "accept",
                  "src": ["group:a"],
                  "dst": ["*:*"]
                },
                {
                  "action": "accept",
                  "src": ["x@example.com"],
                  "dst": ["tag:y:22"]
                }
              ]
            }
        """.trimIndent()
        assertEquals(expected, added)
        // And back: removing the last member takes the comma the previous one got.
        assertEquals(plainJson, SourceEdits.remove(added, p("acls", 1)))
    }

    // ---- empty and missing sections ----

    @Test
    fun anEmptySectionGrowsIntoTheFilesLayout() {
        val text = "{\n\t\"groups\": {},\n\t\"tests\": [],\n}\n"
        val g = SourceEdits.put(text, p("groups"), "group:a", listOf("x@example.com").pv(), emptyList())
        assertEquals("{\n\t\"groups\": {\n\t\t\"group:a\": [\"x@example.com\"],\n\t},\n\t\"tests\": [],\n}\n", g)
        val t = SourceEdits.append(text, p("tests"), PolicyValue.obj("src" to "x@example.com".pv(), "accept" to listOf("tag:a:22").pv()))
        // The file's own fields ("groups": {}, "tests": []) are not aligned, so neither are the new rule's.
        assertEquals("{\n\t\"groups\": {},\n\t\"tests\": [\n\t\t{\n\t\t\t\"src\": \"x@example.com\",\n\t\t\t\"accept\": [\"tag:a:22\"],\n\t\t},\n\t],\n}\n", t)
        val list = SourceEdits.append("{\"a\": []}", p("a"), "x".pv())
        assertEquals("{\"a\": [\"x\"]}", list)
    }

    @Test
    fun aMissingSectionIsCreatedWhereItBelongs() {
        val after = SourceEdits.ensureSection(sample, "hosts", PolicyValue.Obj(emptyList()))
        val w = window(sample, after)
        assertEquals("", w.removed)
        // After groups (the section before it in the usual order), a blank line apart, like the others.
        assertTrue(after.contains("\t},\n\n\t\"hosts\": {},\n\n\t\"tagOwners\": {"))
        assertMeaning(setAt(parsed(sample), listOf("hosts"), json("{}")), after)

        val sshTests = SourceEdits.ensureSection(sample, "sshTests", PolicyValue.Arr(emptyList()))
        assertTrue(sshTests.endsWith("\t],\n\n\t\"sshTests\": [],\n}\n") || sshTests.endsWith("\t],\n\n\t\"sshTests\": [],\n}"))

        val empty = SourceEdits.ensureSection("{}", "acls", PolicyValue.Arr(emptyList()))
        assertEquals("{\n\t\"acls\": [],\n}", empty)
    }

    // ---- move ----

    @Test
    fun moveTakesTheNoteAndTheRulesOwnText() {
        val after = SourceEdits.move(sample, p("acls"), 12, 0, Anchor.AFTER_PREVIOUS)
        assertTrue(after.contains("\t\"acls\": [\n\t\t// --- 4.1 ACCESS FOR EXTERNAL USER ---\n\t\t{\n\t\t\t\"action\": \"accept\",\n\t\t\t\"src\":    [\"carol@example.com\"],"))
        assertMeaning(PolicyTestKit.moveAt(parsed(sample), listOf("acls"), 12, 0), after)
        // The list's opening heading now sits between the moved rule and the old first one.
        assertTrue(after.contains("\t\t},\n\t\t// --- 0. ПРАВИЛА ДЛЯ АДМИНИСТРАТОРОВ"))
        // BEFORE_NEXT at the top lands under that heading instead.
        val under = SourceEdits.move(sample, p("acls"), 12, 0, Anchor.BEFORE_NEXT)
        assertTrue(under.contains("\t\t// устройства админов не видны гостевым устройствам.\n\t\t// --- 4.1 ACCESS FOR EXTERNAL USER ---\n"))
        assertMeaning(PolicyTestKit.moveAt(parsed(sample), listOf("acls"), 12, 0), under)
    }

    @Test
    fun moveAcrossAHeaderDependsOnTheAnchor() {
        // acls[6] is the first DNS rule, under the "2. DNS" header; acls[5] is above the header.
        val under = SourceEdits.move(sample, p("acls"), 5, 6, Anchor.BEFORE_NEXT)
        assertMeaning(PolicyTestKit.moveAt(parsed(sample), listOf("acls"), 5, 6), under)
        val headerLine = PolicyTestKit.lineOf(under, "--- 2. DNS")
        val movedLine = PolicyTestKit.lineOf(under, "\"src\":    [\"tag:master\", \"tag:server\"]")
        assertTrue(movedLine > headerLine)

        // The same index with AFTER_PREVIOUS lifts a rule above its own header.
        val lifted = SourceEdits.move(sample, p("acls"), 6, 6, Anchor.AFTER_PREVIOUS)
        assertEquals(HuJson.canonical(parsed(sample)), HuJson.canonical(parsed(lifted)))
        assertTrue(PolicyTestKit.lineOf(lifted, "\"dst\":    [\"tag:dns:53\"]") < PolicyTestKit.lineOf(lifted, "--- 2. DNS"))
    }

    @Test
    fun moveEveryRuleToEveryPlaceKeepsTheMeaning() {
        val n = (SourceTree.parse(sample).root["ssh"] as SrcArray).members.size
        for (from in 0 until n) for (to in 0 until n) for (anchor in Anchor.entries) {
            val after = SourceEdits.move(sample, p("ssh"), from, to, anchor)
            assertMeaning(PolicyTestKit.moveAt(parsed(sample), listOf("ssh"), from, to), after)
        }
        // Moving inside a one-line list and in JSON style too.
        assertEquals("{\"a\": [\"y\", \"z\", \"x\"]}", SourceEdits.move("{\"a\": [\"x\", \"y\", \"z\"]}", p("a"), 0, 2))
        val json = SourceEdits.move(plainJson.replace("\"group:a\": [\"x@example.com\"]", "\"group:a\": [\"x@example.com\"],\n    \"group:b\": []"), p("groups"), 1, 0)
        assertTrue(json, json.contains("\"group:b\": [],\n    \"group:a\": [\"x@example.com\"]\n  },"))
    }

    @Test
    fun movesThatGoNowhereChangeNothing() {
        assertEquals("{\"a\": [\"x\"]}", SourceEdits.move("{\"a\": [\"x\"]}", p("a"), 0, 0))
        assertEquals(sample, SourceEdits.move(sample, p("ssh"), 1, 1))
        // Under its header with BEFORE_NEXT, a rule stays exactly where it is.
        assertEquals(sample, SourceEdits.move(sample, p("acls"), 6, 6, Anchor.BEFORE_NEXT))
        // There and back again.
        assertEquals(sample, SourceEdits.move(SourceEdits.move(sample, p("ssh"), 0, 2), p("ssh"), 2, 0))
    }

    @Test
    fun aNoteOnAOneLineListBecomesABlockComment() {
        val after = SourceEdits.insert("{\"a\": [\"x\", \"y\"]}", p("a"), 1, "z".pv(), note = "added */ today")
        assertEquals("{\"a\": [\"x\", /* added * / today */ \"z\", \"y\"]}", after)
    }

    @Test
    fun commentsAreWrittenReplacedAndRemoved() {
        // A new note on a rule without one, a blank line above it kept.
        val added = SourceEdits.setComment(sample, p("acls", 7), "Admins reach the DNS panel")
        assertTrue(added.contains("\t\t},\n\t\t// Admins reach the DNS panel\n\t\t{\n\t\t\t\"action\": \"accept\",\n\t\t\t\"src\":    [\"group:prod-admin\", \"group:dev-admin\"],"))
        assertEquals("Admins reach the DNS panel", SourceTree.parse(added).commentsOf(SourceTree.parse(added).root["acls"] as SrcArray, 7).note)
        assertEquals(sample, SourceEdits.setComment(added, p("acls", 7), null))
        // Replacing a two-line note with one line, and writing the same note back changes nothing.
        val replaced = SourceEdits.setComment(sample, p("acls", 11), "Guests: internet only")
        assertTrue(replaced.contains("\t\t},\n\t\t// Guests: internet only\n\t\t{"))
        assertFalse(replaced.contains("К самим нодам"))
        val note = "--- 4.2 ГОСТИ-ПОЛЬЗОВАТЕЛИ: только выход в интернет через exit-ноды ---\nК самим нодам (ssh, панели) доступа нет: для выхода хватает autogroup:internet."
        assertEquals(sample, SourceEdits.setComment(sample, p("acls", 11), note))
        // A heading glued to a rule is the block that rule's comment edits.
        val heading = SourceEdits.setComment(sample, p("acls", 6), "--- 2. DNS ---")
        assertTrue(heading.contains("\n\n\t\t// --- 2. DNS ---\n\t\t{"))
        // Top-level sections too; and a one-line list member is refused, not mangled.
        assertTrue(SourceEdits.setComment(sample, p("tests"), null).endsWith("\t],\n\n\t\"tests\": [\n\t\t{\n\t\t\t\"src\":  \"guest@example.com\",\n\t\t\t\"deny\": [\n\t\t\t\t\"tag:exit-node:22\",\n\t\t\t\t\"tag:master:443\",\n\t\t\t\t\"tag:server:22\",\n\t\t\t\t\"tag:homelab:80\",\n\t\t\t\t\"tag:dns:4000\",\n\t\t\t\t\"alice@example.com:22\",\n\t\t\t],\n\t\t},\n\t],\n}\n"))
        try {
            SourceEdits.setComment(sample, p("acls", 2, "src", 0), "x")
            fail()
        } catch (_: PolicyEditException) {
        }
    }

    // ---- rename ----

    @Test
    fun renameKeepsTheValueColumn() {
        val after = SourceEdits.rename(sample, p("tagOwners", "tag:lab"), "tag:research")
        assertTrue(after.contains("\t\t\"tag:research\":      [\"group:dev-admin\"],\n"))
        assertMeaning(PolicyTestKit.renameAt(parsed(sample), listOf("tagOwners", "tag:lab"), "tag:research"), after)
        // A key longer than the column: one space.
        val long = SourceEdits.rename(sample, p("tagOwners", "tag:lab"), "tag:a-very-long-tag-name")
        assertTrue(long.contains("\t\t\"tag:a-very-long-tag-name\": [\"group:dev-admin\"],\n"))
        try {
            SourceEdits.rename(sample, p("tagOwners", "tag:lab"), "tag:dns")
            fail("a taken key must be refused")
        } catch (_: PolicyEditException) {
        }
    }

    // ---- lists of strings ----

    @Test
    fun setStringsKeepsTheItemsThatStayAndTheirComments() {
        val text = "{\n\t\"dst\": [\n\t\t\"a:22\", // the old box\n\t\t\"b:22\",\n\t\t// c is going away\n\t\t\"c:22\",\n\t],\n}\n"
        val after = SourceEdits.setStrings(text, p("dst"), listOf("a:22", "new:443", "b:22"))
        assertEquals("{\n\t\"dst\": [\n\t\t\"a:22\", // the old box\n\t\t\"new:443\",\n\t\t\"b:22\",\n\t],\n}\n", after)
    }

    @Test
    fun setStringsCreatesMissingFieldsAndReflowsLongLines() {
        val created = SourceEdits.setStrings(sample, p("acls", 2, "srcPosture"), listOf("posture:latest"))
        assertTrue(created.contains("\"src\":    [\"tag:master\"],\n\t\t\t\"srcPosture\": [\"posture:latest\"],\n\t\t\t\"dst\":"))
        val many = (1..10).map { "tag:another-long-name-$it:*" }
        val grown = SourceEdits.setStrings(sample, p("acls", 2, "dst"), listOf("tag:master:*") + many)
        assertTrue(grown.contains("\t\t\t\"dst\":    [\n\t\t\t\t\"tag:master:*\",\n\t\t\t\t\"tag:another-long-name-1:*\",\n"))
        assertEquals(HuJson.canonical(json("[\"tag:master:*\"," + many.joinToString(",") { "\"$it\"" } + "]")),
            HuJson.canonical((HuJson.parse(grown)["acls"] as HuArray).items[2].let { (it as HuObject)["dst"]!! }))
    }

    @Test
    fun everyListAcceptsAnAppendedItem() {
        // A sweep over every array of strings in the file: append, check the meaning.
        val t = SourceTree.parse(sample)
        val paths = mutableListOf<PolicyPath>()
        fun walk(v: SrcValue, path: PolicyPath) {
            when (v) {
                is SrcObject -> v.members.forEach { walk(it.value, path + it.key!!) }
                is SrcArray -> if (v.members.all { it.value is SrcString }) paths += path else v.members.forEachIndexed { i, m -> walk(m.value, path + i) }
                else -> Unit
            }
        }
        walk(t.root, PolicyPath.ROOT)
        assertTrue(paths.size > 30)
        for (path in paths) {
            val after = SourceEdits.append(sample, path, "x@example.com".pv())
            val steps = path.steps.map { if (it is PathStep.Key) it.name else (it as PathStep.Index).index }
            val size = (t.at(path) as SrcArray).members.size
            assertMeaning(insertAt(parsed(sample), steps, size, json("\"x@example.com\"")), after)
            assertEquals("", window(sample, after).removed)
        }
    }

    // ---- misc ----

    @Test
    fun windowsLineEndingsSurviveAnEdit() {
        val crlf = sample.replace("\n", "\r\n")
        val after = SourceEdits.preservingLineEndings(crlf) { SourceEdits.append(it, p("acls", 0, "dst"), "tag:new:22".pv()) }
        assertFalse(after.replace("\r\n", "").contains('\n'))
        assertTrue(after.contains("\t\t\t\t\"tag:new:22\",\r\n"))
    }

    @Test
    fun brokenTextIsRefusedNotGuessed() {
        try {
            SourceEdits.set("{\"a\": [1,", p("a"), "x".pv())
            fail()
        } catch (e: PolicyEditException) {
            assertNotNull(e.message)
        }
        try {
            SourceEdits.remove(sample, p("acls", 99))
            fail()
        } catch (_: PolicyEditException) {
        }
    }

    @Test
    fun oneLineRulesGetOneLineSiblings() {
        val text = "{\n\t\"acls\": [\n\t\t// Allow all connections.\n\t\t{\"action\": \"accept\", \"src\": [\"*\"], \"dst\": [\"*:*\"]},\n\t],\n}\n"
        val after = SourceEdits.append(text, p("acls"), PolicyValue.obj("action" to "accept".pv(), "src" to listOf("tag:a").pv(), "dst" to listOf("tag:b:22").pv()))
        assertTrue(after, after.contains("[\"*:*\"]},\n\t\t{\"action\": \"accept\", \"src\": [\"tag:a\"], \"dst\": [\"tag:b:22\"]},\n\t],"))
        val edited = SourceEdits.setStrings(text, p("acls", 0, "src"), listOf("*", "tag:c"))
        assertTrue(edited.contains("{\"action\": \"accept\", \"src\": [\"*\", \"tag:c\"], \"dst\": [\"*:*\"]},"))
        val field = SourceEdits.put(text, p("acls", 0), "proto", "tcp".pv())
        assertTrue(field, field.contains("{\"action\": \"accept\", \"src\": [\"*\"], \"proto\": \"tcp\", \"dst\": [\"*:*\"]},"))
    }
}
