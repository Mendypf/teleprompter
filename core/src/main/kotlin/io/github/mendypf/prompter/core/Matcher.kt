package io.github.mendypf.prompter.core

/**
 * The reference JavaScript rules (core/tools/reference-matcher.js), unchanged. Positions are key indexes:
 * pos = the next script word not said yet.
 *
 * For each heard word, keeping the last three heard words in mind:
 * - it is the next script word: move on one;
 * - the last two heard words are in a row a little further on (within [NEAR] words): a word was skipped, land after them;
 * - the last three heard words are in a row much further on (within [FAR] words): a sentence was skipped, land after them;
 * - anything else (a repeat, an "um", a misheard word) leaves the text where it is.
 */
class Matcher(private val keys: List<String>) {

    /** Where the text is after hearing [heard], starting from [pos]. Never goes past keys.size. */
    fun advance(pos: Int, heard: List<String>): Int {
        var at = pos
        val tail = ArrayList<String>(4) // the last three heard words
        for (word in heard) {
            tail.add(word)
            if (tail.size > 3) tail.removeAt(0)
            if (at in keys.indices && keys[at] == word) { // the next word: move on one
                at += 1
                continue
            }
            if (tail.size >= 2) { // two in a row a little further on: a word was skipped
                val found = find(tail.subList(tail.size - 2, tail.size), at, at + NEAR)
                if (found >= 0) {
                    at = found + 2
                    continue
                }
            }
            if (tail.size == 3) { // three in a row much further on: a sentence was skipped
                val found = find(tail, at, at + FAR)
                if (found >= 0) {
                    at = found + 3
                    continue
                }
            }
            // anything else leaves the text where it is
        }
        return at
    }

    /** The first place from [from] (and before [to]) where [words] appear in a row in the script; -1 if none. */
    private fun find(words: List<String>, from: Int, to: Int): Int {
        val end = minOf(to, keys.size - words.size + 1)
        for (start in maxOf(0, from) until end) {
            if (words.indices.all { k -> keys[start + k] == words[k] }) return start
        }
        return -1
    }

    companion object {
        /** How far ahead (in words) a skipped word is looked for. */
        const val NEAR = 12

        /** How far ahead (in words) a skipped sentence is looked for. */
        const val FAR = 60
    }
}
