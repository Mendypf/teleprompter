package io.github.mendypf.prompter.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Hand-worked examples of the word-following rules, one step at a time. */
class MatcherTest {

    private val script = Script(Samples.SAMPLE_SCRIPT)
    private val matcher = Matcher(script.keys)

    private fun heard(text: String) = Script.toWords(text)

    @Test
    fun `reading straight moves word by word`() {
        var p = matcher.advance(0, heard("when people bake fresh bread"))
        assertEquals(5, p)
        assertEquals("they", script.tokens[p]) // word 6 (index 5)

        p = matcher.advance(p, heard("they say it feels easy because you can knead a dough in 10 minutes"))
        assertEquals(19, p)
        assertEquals("The", script.tokens[p]) // word 20 (index 19)
    }

    @Test
    fun `a repeat, a pause and an um hold still, then a skipped sentence catches up`() {
        var q = matcher.advance(19, heard("the the trouble is"))
        assertEquals(22, q)
        assertEquals("it", script.tokens[q]) // word 23 "it"

        q = matcher.advance(q, heard(""))
        assertEquals(22, q) // still word 23 "it"

        q = matcher.advance(q, heard("um"))
        assertEquals(22, q) // still word 23 "it"

        q = matcher.advance(q, heard("to make the final twenty percent"))
        assertEquals(40, q)
        assertEquals("you", script.tokens[q]) // word 41 "you"

        // "to make the" was found further on: it lands after word 35 "To", 12 words past "it".
        val landed = matcher.advance(22, heard("to make the")) - 3
        assertEquals(34, landed)
        assertEquals("To", script.tokens[landed])
        assertEquals(12, landed - 22)
    }

    @Test
    fun `the same steps through the Follower land on the same words`() {
        val f = Follower(script)
        assertEquals(5, f.heardFinal("when people bake fresh bread"))
        assertEquals(19, f.heardFinal("they say it feels easy because you can knead a dough in 10 minutes"))
        assertEquals(22, f.heardFinal("the the trouble is"))
        assertEquals(22, f.heardFinal(""))
        assertEquals(22, f.heardFinal("um"))
        assertEquals(40, f.heardFinal("to make the final twenty percent"))
    }

    @Test
    fun `the text never moves past the end`() {
        val end = script.keys.size
        val tail = script.keys.takeLast(3)
        assertEquals(end, matcher.advance(end - 3, tail + tail + listOf("more", "words")))
        assertEquals(end, matcher.advance(end, heard("when people bake fresh bread")))
    }
}
