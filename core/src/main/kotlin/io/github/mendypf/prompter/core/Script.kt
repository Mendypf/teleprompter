package io.github.mendypf.prompter.core

/**
 * The script, split two ways.
 *
 * - [tokens]: the words as the band shows them (the text split on spaces and line breaks, punctuation kept).
 * - [keys]: the spoken words the voice is matched against (lowercase, no punctuation, small numbers spelled out).
 *
 * One token gives no key ("—"), one key ("Hello," gives "hello") or more ("10%" gives "ten" and "percent").
 * Matching works in keys and the band works in tokens; [tokenOfKey] and [firstKeyOfToken] go between the two.
 */
class Script(text: String) {

    /** The words as the band shows them. */
    val tokens: List<String>

    /** True where a token starts a new paragraph: a line break or a blank line comes before it. Never the first token. */
    val paragraphStart: BooleanArray

    /** The spoken words, in order. */
    val keys: List<String>

    private val keyTokens: IntArray // for each key: the token it came from
    private val firstKeys: IntArray // for each token: its first key (the next token's, if it has none)

    init {
        // Cut the text into shown words, noting which ones come right after a line break.
        val shown = ArrayList<String>()
        val newParagraph = ArrayList<Boolean>()
        var lineBreak = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isWhitespace()) {
                if (c == '\n' || c == '\r' || c == ' ' || c == ' ') lineBreak = true
                i++
                continue
            }
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            newParagraph.add(lineBreak && shown.isNotEmpty())
            shown.add(text.substring(start, i))
            lineBreak = false
        }

        // Each shown word gives its spoken words.
        val spoken = ArrayList<String>()
        val owners = ArrayList<Int>()
        val firsts = IntArray(shown.size)
        for ((t, token) in shown.withIndex()) {
            firsts[t] = spoken.size
            for (word in toWords(token)) {
                spoken.add(word)
                owners.add(t)
            }
        }

        tokens = shown
        paragraphStart = newParagraph.toBooleanArray()
        keys = spoken
        keyTokens = owners.toIntArray()
        firstKeys = firsts
    }

    /** The token a key belongs to. The end of the keys (keys.size) is the end of the tokens (tokens.size). */
    fun tokenOfKey(key: Int): Int {
        val k = key.coerceIn(0, keys.size)
        return if (k == keys.size) tokens.size else keyTokens[k]
    }

    /** The first key of a token. A token with nothing to say ("—") gives the next token's first key. */
    fun firstKeyOfToken(token: Int): Int {
        val t = token.coerceIn(0, tokens.size)
        return if (t == tokens.size) keys.size else firstKeys[t]
    }

    companion object {
        /**
         * Turns text into spoken words, the same way for the script and for what the speech AI heard, exactly
         * like the reference JavaScript rules: lowercase; "%" becomes "percent"; a number of one or two digits
         * standing on its own is spelled out ("10" -> "ten", "21" -> "twenty one", "100" stays); apostrophes are
         * dropped ("it's" -> "its"); anything that is not a letter a-z or a digit 0-9 separates words.
         */
        fun toWords(text: String): List<String> =
            text.lowercase()
                .replace("%", " percent")
                .replace(SMALL_NUMBER) { spell(it.value.toInt()) }
                .replace("'", "")
                .replace("’", "") // the curly apostrophe
                .split(SEPARATORS)
                .filter { it.isNotEmpty() }

        // One or two digits with no letter, digit or "_" right before or after them (the reference rules put a word
        // boundary on each side of one or two digits).
        // Written out in full so it behaves the same on a laptop and on the phone.
        private val SMALL_NUMBER = Regex("(?<![A-Za-z0-9_])[0-9]{1,2}(?![A-Za-z0-9_])")
        private val SEPARATORS = Regex("[^a-z0-9]+")

        private val ONES = listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
            "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        )
        private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

        private fun spell(n: Int): String =
            if (n < 20) ONES[n] else TENS[n / 10] + (if (n % 10 != 0) " " + ONES[n % 10] else "")
    }
}
