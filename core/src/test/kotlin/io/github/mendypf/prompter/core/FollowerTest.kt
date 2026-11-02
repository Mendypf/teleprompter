package io.github.mendypf.prompter.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowerTest {

    private val sample = Script(Samples.SAMPLE_SCRIPT)

    @Test
    fun `a partial the speech AI rewrites never counts the same words twice`() {
        val f = Follower(Script("one two three one two three four"))
        assertEquals(1, f.heardPartial("one"))
        assertEquals(2, f.heardPartial("one two"))
        // Matched from the last partial's place instead, "one two three" would run on from word 2 and land at 6.
        assertEquals(3, f.heardPartial("one two three"))
        assertEquals(3, f.heardPartial("one two three")) // the same partial again: the same place
        // The speech AI changes its guess for the second word: matched again from the utterance's start.
        assertEquals(1, f.heardPartial("one to three"))
        assertEquals(5, f.heardPartial("one two three one two"))
        assertEquals(5, f.heardFinal("one two three one two"))
    }

    @Test
    fun `a final commits, so the next utterance starts where it ended`() {
        val f = Follower(sample)
        assertEquals(3, f.heardPartial("when people bake"))
        assertEquals(5, f.heardFinal("when people bake fresh bread"))
        assertEquals(6, f.heardPartial("they")) // from word 5; from the start "they" would not move
        assertEquals(7, f.heardPartial("they say"))
        // A final can also rewrite what the partials said; it is matched from the utterance's start too.
        assertEquals(6, f.heardFinal("they sag"))
        assertEquals(7, f.heardPartial("say"))
    }

    @Test
    fun `repeated words hold, then the text moves on`() {
        val f = Follower(sample)
        f.jumpTo(19) // "The trouble is"
        assertEquals(20, f.heardPartial("the"))
        assertEquals(20, f.heardPartial("the the"))
        assertEquals(20, f.heardPartial("the the the"))
        assertEquals(21, f.heardPartial("the the the trouble"))
        assertEquals(21, f.heardPartial("the the the trouble th- th-"))
        assertEquals(22, f.heardFinal("the the the trouble th- th- is"))
    }

    @Test
    fun `empty and filler partials hold`() {
        val f = Follower(sample)
        assertEquals(0, f.heardPartial(""))
        assertEquals(0, f.heardPartial("um"))
        assertEquals(0, f.heardPartial("um, uh..."))
        assertEquals(2, f.heardPartial("um, uh... when people"))
        assertEquals(2, f.heardPartial("um, uh... when people um"))
        assertEquals(2, f.heardPartial("")) // a pause mid-utterance holds; it does not jump back
        assertEquals(2, f.heardPartial("..."))
        assertEquals(2, f.heardFinal("")) // an empty final keeps the place
        assertEquals(3, f.heardPartial("bake"))
    }

    @Test
    fun `jumpTo continues from the tapped word`() {
        val f = Follower(sample)
        f.jumpTo(15) // "dough"
        assertEquals(15, f.position)
        assertEquals(17, f.heardPartial("dough in"))
        assertEquals(19, f.heardFinal("dough in ten minutes"))
    }

    @Test
    fun `a tap during an utterance is not undone by words heard before it`() {
        val f = Follower(sample)
        assertEquals(7, f.heardPartial("when people bake fresh bread they say"))
        f.jumpTo(0) // the speaker taps "When" to say the sentence again
        assertEquals(0, f.position)
        // Still the same utterance: its partials repeat the words from before the tap, which stay behind.
        assertEquals(0, f.heardPartial("when people bake fresh bread they say"))
        assertEquals(2, f.heardPartial("when people bake fresh bread they say when people"))
        assertEquals(3, f.heardFinal("when people bake fresh bread they say when people bake"))
        assertEquals(4, f.heardPartial("fresh")) // the next utterance starts fresh from the tapped place
    }

    @Test
    fun `after a tap, a new utterance with fewer words is used in full`() {
        val f = Follower(sample)
        f.heardPartial("when people bake fresh bread they say") // no final: the speech AI started over
        f.jumpTo(15)
        assertEquals(16, f.heardPartial("dough"))
    }

    @Test
    fun `restart goes back to the first word with nothing heard`() {
        val f = Follower(sample)
        f.heardFinal("when people bake fresh bread")
        f.heardPartial("they say it") // mid-utterance when the speaker restarts
        f.restart()
        assertEquals(0, f.position)
        assertFalse(f.finished)
        assertEquals(1, f.heardPartial("when"))
        assertEquals(2, f.heardFinal("when people"))
    }

    @Test
    fun `the position never goes past the end`() {
        val s = Script("Hello world")
        val f = Follower(s)
        assertEquals(2, f.heardFinal("hello world hello world and some more words"))
        assertEquals(s.tokens.size, f.position)
        assertEquals(2, f.heardPartial("world world"))
        f.jumpTo(99)
        assertEquals(2, f.position)
        f.jumpTo(-3)
        assertEquals(0, f.position)
    }

    @Test
    fun `finished is true at the end, even with a dash after the last word`() {
        val s = Script("That is all —")
        val f = Follower(s)
        assertFalse(f.finished)
        assertEquals(2, f.heardPartial("that is"))
        assertFalse(f.finished)
        assertEquals(4, f.heardFinal("that is all"))
        assertEquals(s.tokens.size, f.position)
        assertTrue(f.finished)
    }

    @Test
    fun `an empty script is finished from the start`() {
        val f = Follower(Script(""))
        assertTrue(f.finished)
        assertEquals(0, f.position)
        assertEquals(0, f.heardFinal("hello"))
    }

    @Test
    fun `a long script read straight through ends at the end`() {
        val script = Script(Samples.LONG_SCRIPT)
        val positions = readAloud(script, Random(1)) { word, _ -> listOf(word) }
        assertEquals(script.tokens.size, positions.last())
    }

    @Test
    fun `the long script with repeats, um and pauses still ends at the end`() {
        val script = Script(Samples.LONG_SCRIPT)
        val positions = readAloud(script, Random(2)) { word, rnd ->
            val said = mutableListOf<String>()
            if (rnd.nextInt(8) == 0) said += "um"
            val part = word.take(2).lowercase() + "-" // a part of the word, like "th-"
            if (rnd.nextInt(8) == 0 && Script.toWords(part).none { it in script.keys }) said += part
            repeat(if (rnd.nextInt(6) == 0) 1 + rnd.nextInt(3) else 1) { said += word }
            said
        }
        assertEquals(script.tokens.size, positions.last())
    }

    /**
     * Reads [script] aloud the way the speech AI hears it: utterances of a few words, partials growing one word at a
     * time with an empty one now and then, and a final at the end of each utterance. [say] turns one shown word into
     * what is said for it. Checks the text never moves back and lands on the next word after every final.
     */
    private fun readAloud(script: Script, rnd: Random, say: (String, Random) -> List<String>): List<Int> {
        val f = Follower(script)
        val positions = mutableListOf(f.position)
        var t = 0
        while (t < script.tokens.size) {
            val n = minOf(1 + rnd.nextInt(12), script.tokens.size - t)
            val heard = script.tokens.subList(t, t + n).flatMap { say(it, rnd) }
            for (k in 1..heard.size) {
                if (rnd.nextInt(5) == 0) positions += f.heardPartial("") // a pause
                positions += f.heardPartial(heard.take(k).joinToString(" "))
            }
            positions += f.heardFinal(heard.joinToString(" "))
            t += n
            // After the final, the next word to say is the next shown word that has something to say.
            assertEquals(script.tokenOfKey(script.firstKeyOfToken(t)), positions.last(), "after \"${heard.joinToString(" ")}\"")
        }
        assertEquals(positions.sorted(), positions, "the text moved back")
        assertTrue(f.finished)
        return positions
    }
}
