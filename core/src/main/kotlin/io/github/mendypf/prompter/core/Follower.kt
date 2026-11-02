package io.github.mendypf.prompter.core

/**
 * Follows the speaker through one script and keeps the next word to say. Positions are token indexes for the band.
 *
 * The speech AI hands over what it heard in pieces. While someone talks it sends "partials": each one is the whole
 * utterance so far, and it may still rewrite earlier words. When the utterance ends it sends a "final".
 * So every partial is matched again from where its utterance began, never from where the last partial landed:
 * a rewritten partial can never count the same words twice. Repeats, "um"s and pauses leave the text where it is.
 *
 * Use it from one thread. If the speech AI stops without a final (Stop, or it starts over), call heardFinal("")
 * so the next words start fresh from the current place.
 */
class Follower(val script: Script) {

    private val matcher = Matcher(script.keys)
    private var next = 0 // the next word to say, as a key index
    private var start = 0 // where the current utterance began, as a key index
    private var heardCount = 0 // how many words the latest partial of this utterance had
    private var skip = 0 // words of this utterance heard before a tap: they belong to the old place

    /** Token index of the next word to say; tokens.size once the whole script is said. */
    val position: Int get() = script.tokenOfKey(next)

    /** True once the whole script is said. */
    val finished: Boolean get() = next >= script.keys.size

    /** The utterance so far. It is matched again from the utterance's start. Returns the new position. */
    fun heardPartial(text: String): Int {
        val words = Script.toWords(text)
        if (words.isEmpty()) return position // nothing heard yet: hold still
        if (words.size < skip) skip = 0 // fewer words than before the tap: a new utterance began
        heardCount = words.size
        next = matcher.advance(start, words.drop(skip)).coerceIn(0, script.keys.size)
        return position
    }

    /** The whole utterance, matched like a partial. The next utterance then starts where this one ended. */
    fun heardFinal(text: String): Int {
        heardPartial(text)
        start = next
        heardCount = 0
        skip = 0
        return position
    }

    /** The speaker tapped a word: continue from there. Words already heard in this utterance are not used again. */
    fun jumpTo(token: Int) {
        next = script.firstKeyOfToken(token)
        start = next
        skip = heardCount
    }

    /** Back to the first word, with nothing heard yet. */
    fun restart() {
        next = 0
        start = 0
        heardCount = 0
        skip = 0
    }
}
