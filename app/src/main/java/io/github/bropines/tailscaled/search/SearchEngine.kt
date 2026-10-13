package io.github.bropines.tailscaled.search

/*
 * The search over a catalogue of entries, each a handful of texts: titles, keywords, where
 * the entry lives, what it does — in as many languages as the caller hands in.
 *
 * A query is cut into words. Every word has to be found in an entry, in any order and in
 * any of its texts; the little words of a question ("how do I…", «как включить…») count
 * for a little when they are found and for nothing when they are not. A word is found when
 * a word of the text is it, begins with it, is another form of it, sounds like it in the
 * other alphabet, contains it, or is a slip of the finger away. When the query as typed
 * finds nothing that way, it is read once more as the same keys in the other layout.
 *
 * The order: a title before a keyword before the place before the description, each of them
 * before any slip of the finger; among equals the entry the words cover most of; then the
 * caller's own nudge (what was opened before); then the order of the catalogue.
 */

/** Which of an entry's texts a text is. Its weight is what a word found in it is worth. */
enum class SearchField(internal val weight: Int, internal val slip: Int) {
    TITLE(1000, 260),
    KEYWORD(700, 230),
    /** Where the entry lives: its section and its card. */
    PLACE(500, 200),
    DESCRIPTION(400, 170),
}

/** One thing to be found, with every text it is found by. */
class SearchEntry(val id: String, val texts: List<Pair<SearchField, String>>)

/**
 * An entry the query found. [field] and [text] are where its words stood best — a keyword,
 * say, to show beside a title that has none of them. [solid] is false when a word was only
 * found as a slip of the finger.
 */
class SearchHit(val id: String, val score: Int, val field: SearchField, val text: String, val solid: Boolean)

/**
 * What a query found. [closest] says that nothing had all of its words and these are the
 * entries with the most of them; [readAs] is the query in the other keyboard layout, when
 * that is how it was understood.
 */
class SearchResult internal constructor(
    val query: String,
    val hits: List<SearchHit>,
    val closest: Boolean,
    val readAs: String?,
    internal val terms: List<SearchEngine.Term>,
)

class SearchEngine(entries: List<SearchEntry>, stopWords: Set<String> = emptySet()) {

    internal class Term(val word: String, val optional: Boolean) {
        val stem: String = SearchText.stem(word)
        val skeleton: String = SearchText.skeleton(word)
    }

    private class Text(val entry: Int, val field: SearchField, val text: String, val letters: Int)

    /** A distinct word of the catalogue and the texts it stands in. */
    private class Lexeme(val word: String, val skeleton: String, val texts: IntArray)

    private val ids: List<String> = entries.map { it.id }
    private val texts: List<Text>
    private val entryTexts: Array<IntArray>
    private val lexemes: List<Lexeme>
    private val stops: Set<String> = stopWords.flatMapTo(HashSet()) { stop -> SearchText.words(stop).map { it.text } }

    /** What each typed word matched, kept across keystrokes: a query grows by its last word only. */
    private val matched = object : LinkedHashMap<String, IntArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?): Boolean = size > 64
    }

    init {
        val all = ArrayList<Text>()
        val byWord = LinkedHashMap<String, MutableList<Int>>()
        val perEntry = ArrayList<IntArray>()
        entries.forEachIndexed { index, entry ->
            val mine = ArrayList<Int>()
            // The same words twice — a title that reads alike in two languages — are one text.
            for ((field, text) in entry.texts.distinct()) {
                val words = SearchText.words(text, compounds = true)
                if (words.isEmpty()) continue
                val at = all.size
                all.add(Text(index, field, text, SearchText.words(text).sumOf { it.text.length }))
                mine.add(at)
                for (word in words) {
                    val list = byWord.getOrPut(word.text) { ArrayList() }
                    if (list.lastOrNull() != at) list.add(at)
                }
            }
            perEntry.add(mine.toIntArray())
        }
        texts = all
        entryTexts = perEntry.toTypedArray()
        lexemes = byWord.map { (word, where) -> Lexeme(word, SearchText.skeleton(word), where.toIntArray()) }
    }

    /**
     * The entries [query] finds, best first, [limit] at most. [boost] is added to an entry's
     * score — small, or it would outweigh what was typed. A blank query finds nothing.
     */
    fun search(query: String, boost: (String) -> Int = { 0 }, limit: Int = 40): SearchResult {
        val typed = run(query, null, boost, limit)
        if (typed.hits.any { it.solid } && !typed.closest) return typed
        // Nothing sure as typed: the same keys in the other layout.
        val other = listOfNotNull(SearchText.inCyrillicLayout(query), SearchText.inLatinLayout(query))
            .map { run(it, it.trim(), boost, limit) }
            .filter { it.hits.isNotEmpty() }
        val sure = other.firstOrNull { read -> !read.closest && read.hits.any { it.solid } }
        return when {
            sure != null -> sure
            typed.hits.isNotEmpty() && !typed.closest -> typed
            else -> other.firstOrNull { !it.closest } ?: typed.takeIf { it.hits.isNotEmpty() } ?: other.firstOrNull() ?: typed
        }
    }

    /**
     * The parts of [text] that the words of [result]'s query were found in, to be marked:
     * the letters typed where a word begins with them or contains them, the whole word where
     * it was reached another way.
     */
    fun highlight(text: String, result: SearchResult): List<IntRange> {
        val terms = result.terms.filter { !it.optional }.ifEmpty { result.terms }
        if (terms.isEmpty()) return emptyList()
        val ranges = ArrayList<IntRange>()
        for (word in SearchText.words(text)) {
            var best = 0
            var range: IntRange? = null
            for (term in terms) {
                val grade = grade(term, word.text, SearchText.skeleton(word.text))
                if (grade <= best) continue
                best = grade
                range = when {
                    grade == EXACT -> word.start until word.end
                    grade >= PREFIX -> word.start until word.start + term.word.length
                    grade == STEM_EXACT || grade == STEM_PREFIX -> word.start until word.start + term.stem.length
                    grade == INFIX -> (word.start + word.text.indexOf(term.word)).let { it until it + term.word.length }
                    else -> word.start until word.end
                }
            }
            range?.let { ranges.add(it) }
        }
        return ranges
    }

    // ─── One reading of a query ──────────────────────────────────────────────────────────

    private fun parse(query: String): List<Term> {
        val words = SearchText.words(query).map { it.text }.distinct()
        // A query of little words only is asked as it stands.
        val allStops = words.all { it in stops }
        return words.map { Term(it, optional = !allStops && it in stops) }
    }

    private fun run(query: String, readAs: String?, boost: (String) -> Int, limit: Int): SearchResult {
        val terms = parse(query)
        if (terms.isEmpty()) return SearchResult(query, emptyList(), false, readAs, terms)
        val count = terms.size
        // The grade of each term in each text, a row per text.
        val grades = IntArray(texts.size * count)
        terms.forEachIndexed { t, term ->
            val found = matches(term)
            var i = 0
            while (i < found.size) {
                val lexeme = lexemes[found[i]]
                val grade = found[i + 1]
                for (text in lexeme.texts) {
                    val cell = text * count + t
                    // A one- or two-letter word begins too many words to be looked for in running text.
                    if (term.word.length <= 2 && texts[text].field == SearchField.DESCRIPTION) continue
                    if (grade > grades[cell]) grades[cell] = grade
                }
                i += 2
            }
        }

        val required = terms.indices.filter { !terms[it].optional }
        val full = ArrayList<SearchHit>()
        val part = ArrayList<SearchHit>()
        for (entry in ids.indices) {
            val best = IntArray(count)
            val bestGrade = IntArray(count)
            val bestText = IntArray(count) { -1 }
            // The text that has all the words itself, and reads best with them.
            var whole = -1
            var wholeSum = 0
            for (text in entryTexts[entry]) {
                var sum = 0
                var all = true
                for (t in 0 until count) {
                    val grade = grades[text * count + t]
                    val score = score(texts[text].field, grade)
                    if (score > best[t]) {
                        best[t] = score
                        bestGrade[t] = grade
                        bestText[t] = text
                    }
                    if (!terms[t].optional) {
                        if (grade == 0) all = false else sum += score
                    }
                }
                if (all && sum > wholeSum) {
                    whole = text
                    wholeSum = sum
                }
            }
            val found = required.count { best[it] > 0 }
            if (found == 0) continue
            val apart = required.sumOf { best[it] } / required.size
            val together = if (whole >= 0) wholeSum / required.size + TOGETHER else 0
            val lead = if (together >= apart && whole >= 0) whole else bestText[required.maxByOrNull { best[it] }!!]
            // How much of the leading text the words are: "DNS" before "DNS proxy address".
            val covered = required.sumOf { t -> if (grades[lead * count + t] > 0) terms[t].word.length else 0 }
            val specific = SPECIFIC * minOf(covered, texts[lead].letters) / texts[lead].letters
            val asides = terms.indices.count { terms[it].optional && bestGrade[it] >= SOLID } * ASIDE
            // Found in a title and in a keyword too: the entry is about the word, not merely named with it.
            val twice = required.count { t ->
                bestGrade[t] >= SOLID && entryTexts[entry].any { grades[it * count + t] >= SOLID && texts[it].field != texts[bestText[t]].field }
            } * TWICE / required.size
            var total = maxOf(apart, together) + specific + asides + twice + boost(ids[entry])
            val solid = required.all { best[it] == 0 || bestGrade[it] >= SOLID }
            if (found < required.size) total = total * found / required.size
            val hit = SearchHit(ids[entry], total, texts[lead].field, texts[lead].text, solid)
            if (found == required.size) full.add(hit) else part.add(hit)
        }

        val closest = full.isEmpty() && part.isNotEmpty()
        // sortedByDescending is stable: equals stay in the catalogue's order.
        var hits = (if (closest) part else full).sortedByDescending { it.score }
        // A slip of the finger fills in when little is found for certain, and is noise beside plenty.
        val sure = hits.count { it.solid }
        if (sure >= ENOUGH) hits = hits.filter { it.solid }
        return SearchResult(query, hits.take(limit), closest, readAs, terms)
    }

    /** The lexemes [term] is found in, as pairs of index and grade. */
    private fun matches(term: Term): IntArray {
        synchronized(matched) { matched[term.word] }?.let { return it }
        val found = ArrayList<Int>()
        lexemes.forEachIndexed { index, lexeme ->
            val grade = grade(term, lexeme.word, lexeme.skeleton)
            if (grade > 0) {
                found.add(index)
                found.add(grade)
            }
        }
        return found.toIntArray().also { synchronized(matched) { matched[term.word] = it } }
    }

    private companion object {
        // How a typed word was found in a word of a text, best first. Whole numbers out of a
        // thousand; under [SOLID] it is a slip of the finger.
        const val EXACT = 1000
        /** The word begins with it; up to 79 more for how much of the word it is. */
        const val PREFIX = 900
        /** The word is its stem: "proxies" for "proxy". */
        const val STEM_EXACT = 890
        /** The word sounds like it in the other alphabet: «прокси» for "proksi". */
        const val SOUND_EXACT = 850
        const val STEM_PREFIX = 800
        const val SOUND_PREFIX = 750
        /** The word contains it: "MagicDNS" for "dns". */
        const val INFIX = 700
        const val SOLID = 100
        /** A slip of the finger, out of [SLIP]: one slip, a second one, and the same in a word still being typed. */
        const val SLIP = 60
        const val SLIP_MORE = 15
        const val SLIP_BEGINNING = 40
        const val SLIP_SOUND = 35

        /** For a text that holds every word of the query itself. */
        const val TOGETHER = 60
        /** For words that are all of the text they were found in; a part of it for a part. */
        const val SPECIFIC = 40
        /** For a little word of the question that was found too. */
        const val ASIDE = 10
        /** For a word found in two kinds of an entry's texts. */
        const val TWICE = 15
        /** With this many sure hits, the unsure ones are left out. */
        const val ENOUGH = 5

        fun score(field: SearchField, grade: Int): Int = when {
            grade >= SOLID -> field.weight * grade / 1000
            grade > 0 -> field.slip * grade / SLIP
            else -> 0
        }

        fun grade(term: Term, word: String, skeleton: String): Int {
            val typed = term.word
            if (word == typed) return EXACT
            if (word.startsWith(typed)) return PREFIX + 80 * typed.length / word.length
            if (typed.length < 3) return 0
            val stem = term.stem
            // A word of soft and hard signs alone has no sound to compare, and two letters of it say little.
            val sound = term.skeleton.takeIf { it.length >= 3 }
            if (stem != typed && word == stem) return STEM_EXACT
            if (sound != null && skeleton == sound) return SOUND_EXACT
            if (stem != typed && word.startsWith(stem)) return STEM_PREFIX
            if (sound != null && skeleton.startsWith(sound)) return SOUND_PREFIX
            if (word.contains(typed)) return INFIX
            if (typed.length < 4) return 0
            val max = if (typed.length >= 8) 2 else 1
            val (whole, beginning) = SearchText.slips(typed, word, max)
            // A short word with another first letter is another word, not a slip.
            if (whole != SearchText.TOO_FAR && (typed.length >= 6 || typed[0] == word[0])) return SLIP - SLIP_MORE * (whole - 1)
            if (beginning != SearchText.TOO_FAR && typed.length >= 5 && word.length > typed.length) {
                return SLIP_BEGINNING - SLIP_MORE * (beginning - 1)
            }
            // The slip made in the other alphabet: "blokirowki".
            if (sound != null && sound.length >= 4 && skeleton.isNotEmpty() && sound[0] == skeleton[0] && (sound != typed || skeleton != word)) {
                val (slips, _) = SearchText.slips(sound, skeleton, if (sound.length >= 8) 2 else 1)
                if (slips != SearchText.TOO_FAR) return SLIP_SOUND - SLIP_MORE * (slips - 1)
            }
            return 0
        }
    }
}
