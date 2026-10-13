package io.github.bropines.tailscaled.search

import java.text.Normalizer

/*
 * Text as the search compares it: folded letter by letter and cut into words,
 * and the other readings a typed word may have — the same keys in the other
 * keyboard layout, the same sounds in the other alphabet, the same word in
 * another form, the same word with a slip of the finger.
 *
 * Nothing of Android here: the engine and its tests run on a plain JVM.
 */

/** A word of a text as it is compared, and where it stands in the text it came from. */
class Word(val text: String, val start: Int, val end: Int)

internal object SearchText {

    /**
     * One character as it is compared: lower case, «ё» as «е», a Latin letter
     * without its accent. Always one character for one, so a position in the
     * folded text is the same position in the original.
     */
    fun fold(c: Char): Char {
        val lower = c.lowercaseChar()
        return when {
            lower < '\u0080' -> lower
            lower == 'ё' -> 'е'
            // Latin-1 Supplement and Latin Extended: é → e. Cyrillic is left alone — «й» would lose its breve.
            lower in 'À'..'ɏ' -> Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)[0]
            else -> lower
        }
    }

    /**
     * The words of [text]: runs of letters and digits, folded. With [compounds], two words
     * joined by a hyphen are also given as one — "wi-fi" is found by "wifi" as well.
     */
    fun words(text: String, compounds: Boolean = false): List<Word> {
        val out = ArrayList<Word>()
        val word = StringBuilder()
        var start = 0
        var previous: Word? = null
        for (i in 0..text.length) {
            val c = if (i < text.length) text[i] else ' '
            if (Character.isLetterOrDigit(c)) {
                if (word.isEmpty()) start = i
                word.append(fold(c))
                continue
            }
            if (word.isEmpty()) continue
            val current = Word(word.toString(), start, i)
            word.setLength(0)
            out.add(current)
            val before = previous
            if (compounds && before != null && before.end == current.start - 1 && text[before.end] == '-') {
                out.add(Word(before.text + current.text, before.start, current.end))
            }
            previous = current
        }
        return out
    }

    // ─── The other keyboard layout ───────────────────────────────────────────────────────

    private const val LATIN_KEYS = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`{}:\"<>~"
    private const val CYRILLIC_KEYS = "йцукенгшщзхъфывапролджэячсмитьбюёхъжэбюё"

    /**
     * [text] as the same keys give it in the Russian layout — "ghjrcb" is «прокси» — or null
     * when it has no Latin letter to read that way.
     */
    fun inCyrillicLayout(text: String): String? {
        if (text.none { it.lowercaseChar() in 'a'..'z' }) return null
        return buildString(text.length) {
            for (c in text) {
                val key = LATIN_KEYS.indexOf(c.lowercaseChar())
                append(if (key >= 0) CYRILLIC_KEYS[key] else c)
            }
        }
    }

    /** [text] as the same keys give it in the Latin layout — «мзт» is "vpn" — or null without a Cyrillic letter. */
    fun inLatinLayout(text: String): String? {
        if (text.none { it.lowercaseChar() in 'а'..'я' || it.lowercaseChar() == 'ё' }) return null
        return buildString(text.length) {
            for (c in text) {
                // The first of the two keys a letter stands on: the unshifted one.
                val key = CYRILLIC_KEYS.indexOf(c.lowercaseChar())
                append(if (key >= 0) LATIN_KEYS[key] else c)
            }
        }
    }

    // ─── The other alphabet ──────────────────────────────────────────────────────────────

    private val CYRILLIC_SOUNDS: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "j", 'з' to "z",
        'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p",
        'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch",
        'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "i", 'ь' to "", 'э' to "e", 'ю' to "iu", 'я' to "ia",
        'і' to "i", 'ї' to "i", 'є' to "e", 'ґ' to "g",
    )

    /**
     * A folded word reduced to how it sounds, in Latin letters, so that a word and its
     * transliteration come out the same: «прокси», "proksi" and "proxy" are all "proksi";
     * «настройки», "nastroiki" and "nastroyki" are "nastroiki"; «впн» is "vpn".
     *
     * It is a key to compare by, never shown: the spellings people use for one sound are
     * folded onto one (y and i, x and ks, zh and j, kh and h, w and v), and doubled letters
     * onto single ones.
     */
    fun skeleton(word: String): String {
        val latin = StringBuilder(word.length + 4)
        for (c in word) latin.append(CYRILLIC_SOUNDS[c] ?: c)
        val out = StringBuilder(latin.length)
        var i = 0
        fun at(offset: Int): Char = latin.getOrElse(i + offset) { ' ' }
        fun put(sound: String, taken: Int) {
            for (c in sound) if (out.isEmpty() || out.last() != c) out.append(c)
            i += taken
        }
        while (i < latin.length) {
            val c = latin[i]
            when {
                c == 's' && at(1) == 'h' && at(2) == 'c' && at(3) == 'h' -> put("sch", 4)
                c == 'z' && at(1) == 'h' -> put("j", 2)
                c == 'k' && at(1) == 'h' -> put("h", 2)
                c == 'p' && at(1) == 'h' -> put("f", 2)
                c == 'c' && at(1) == 'k' -> put("k", 2)
                c == 'c' && at(1) == 'h' -> put("ch", 2)
                c == 'c' -> put(if (at(1) == 'e' || at(1) == 'i' || at(1) == 'y') "s" else "k", 1)
                c == 'x' -> put("ks", 1)
                c == 'w' -> put("v", 1)
                c == 'q' -> put("k", 1)
                c == 'y' -> put("i", 1)
                else -> put(c.toString(), 1)
            }
        }
        return out.toString()
    }

    // ─── Another form of the word ────────────────────────────────────────────────────────

    // Longest first: the first that fits is taken.
    private val RUSSIAN_ENDINGS = listOf(
        "иями", "ями", "ами", "ией", "иям", "ием", "иях", "ого", "его", "ому", "ему",
        "ов", "ев", "ей", "ой", "ий", "ый", "ая", "яя", "ое", "ее", "ые", "ие", "ом", "ем", "ам", "ям",
        "ах", "ях", "ую", "юю", "ть", "а", "я", "ы", "и", "е", "о", "у", "ю", "ь",
    )

    /**
     * [word] without its ending, as far as a few rules can tell: «блокировку» and «блокировки»
     * both begin with «блокировк», "proxies" is "proxy", "ports" is "port". The word itself
     * where nothing is to be cut or too little would be left. A query's stem is matched as
     * the beginning of a word, so cutting a letter too many costs nothing.
     */
    fun stem(word: String): String {
        if (word.isEmpty()) return word
        val last = word.last()
        if (last in 'а'..'я') {
            for (ending in RUSSIAN_ENDINGS) {
                if (word.length - ending.length >= 3 && word.endsWith(ending)) return word.dropLast(ending.length)
            }
            return word
        }
        if (last !in 'a'..'z') return word
        return when {
            word.length >= 5 && word.endsWith("ies") -> word.dropLast(3) + "y"
            word.length >= 6 && word.endsWith("ing") -> word.dropLast(3)
            word.length >= 5 && word.endsWith("ed") -> word.dropLast(2)
            word.length >= 5 && word.endsWith("es") -> word.dropLast(2)
            word.length >= 4 && last == 's' && word[word.length - 2] != 's' -> word.dropLast(1)
            else -> word
        }
    }

    // ─── A slip of the finger ────────────────────────────────────────────────────────────

    /** A distance over [max]: no match. */
    const val TOO_FAR = Int.MAX_VALUE

    /**
     * How many slips turn [typed] into [word] — a wrong letter, a missing one, an extra one,
     * two neighbours swapped — and how many turn it into the beginning of [word], for a word
     * that is still being typed. Both are [TOO_FAR] above [max]. Packed as `whole to beginning`.
     *
     * The optimal string alignment distance, cut off at [max]: a row whose smallest cell is
     * already over it ends the count.
     */
    fun slips(typed: String, word: String, max: Int): Pair<Int, Int> {
        val n = typed.length
        val m = word.length
        if (m < n - max) return TOO_FAR to TOO_FAR
        // A beginning longer than the typed word plus its slips cannot be nearer than a shorter one.
        val width = minOf(m, n + max)
        var before = IntArray(width + 1)
        var row = IntArray(width + 1) { it }
        var next = IntArray(width + 1)
        for (i in 1..n) {
            next[0] = i
            var smallest = i
            for (j in 1..width) {
                val cost = if (typed[i - 1] == word[j - 1]) 0 else 1
                var d = minOf(row[j] + 1, next[j - 1] + 1, row[j - 1] + cost)
                if (i > 1 && j > 1 && typed[i - 1] == word[j - 2] && typed[i - 2] == word[j - 1]) {
                    d = minOf(d, before[j - 2] + 1)
                }
                next[j] = d
                if (d < smallest) smallest = d
            }
            if (smallest > max) return TOO_FAR to TOO_FAR
            val spare = before
            before = row
            row = next
            next = spare
        }
        val whole = if (m <= width && row[m] <= max) row[m] else TOO_FAR
        var beginning = TOO_FAR
        for (j in 1..width) if (row[j] <= max && row[j] < beginning) beginning = row[j]
        return whole to beginning
    }
}
