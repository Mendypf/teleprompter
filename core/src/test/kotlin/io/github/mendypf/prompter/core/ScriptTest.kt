package io.github.mendypf.prompter.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ScriptTest {

    @Test
    fun `punctuation stays on the shown words and leaves the spoken words`() {
        val s = Script("Hello, world!")
        assertEquals(listOf("Hello,", "world!"), s.tokens)
        assertEquals(listOf("hello", "world"), s.keys)
        assertContentEquals(booleanArrayOf(false, false), s.paragraphStart)
        assertEquals(listOf(0, 1, 2), (0..2).map { s.tokenOfKey(it) })
        assertEquals(listOf(0, 1, 2), (0..2).map { s.firstKeyOfToken(it) })
    }

    @Test
    fun `a percent sign gives two spoken words for one shown word`() {
        val s = Script("10% of AI's users")
        assertEquals(listOf("10%", "of", "AI's", "users"), s.tokens)
        assertEquals(listOf("ten", "percent", "of", "ais", "users"), s.keys)
        // Both "ten" and "percent" belong to "10%"; the end of the keys is the end of the tokens.
        assertEquals(listOf(0, 0, 1, 2, 3, 4), (0..5).map { s.tokenOfKey(it) })
        assertEquals(listOf(0, 2, 3, 4, 5), (0..4).map { s.firstKeyOfToken(it) })
    }

    @Test
    fun `a decimal number gives one spoken word per digit group`() {
        val s = Script("it's 1.5 GB")
        assertEquals(listOf("it's", "1.5", "GB"), s.tokens)
        assertEquals(listOf("its", "one", "five", "gb"), s.keys)
        assertEquals(listOf(0, 1, 1, 2, 3), (0..4).map { s.tokenOfKey(it) })
        assertEquals(listOf(0, 1, 3, 4), (0..3).map { s.firstKeyOfToken(it) })
    }

    @Test
    fun `line breaks and blank lines start paragraphs`() {
        val s = Script("First line here.\n\nSecond paragraph starts.\nA new line\r\nand a Windows one.")
        assertEquals(
            listOf("First", "line", "here.", "Second", "paragraph", "starts.", "A", "new", "line", "and", "a", "Windows", "one."),
            s.tokens,
        )
        val starts = s.tokens.indices.filter { s.paragraphStart[it] }
        assertEquals(listOf(3, 6, 9), starts) // "Second", "A", "and"
    }

    @Test
    fun `the first word is never a paragraph start, even after blank lines`() {
        val s = Script("\n\n   Hello\n")
        assertEquals(listOf("Hello"), s.tokens)
        assertContentEquals(booleanArrayOf(false), s.paragraphStart)
    }

    @Test
    fun `a shown word with no letters or digits has nothing to say`() {
        val s = Script("Wait — what?")
        assertEquals(listOf("Wait", "—", "what?"), s.tokens)
        assertEquals(listOf("wait", "what"), s.keys)
        assertEquals(1, s.firstKeyOfToken(1)) // "—" gives the next word's key
        assertEquals(2, s.tokenOfKey(1)) // "what" is the third shown word

        val end = Script("The end —")
        assertEquals(listOf("the", "end"), end.keys)
        assertEquals(2, end.firstKeyOfToken(2)) // nothing after "—": the end of the keys
        assertEquals(3, end.tokenOfKey(2)) // ...which is the end of the tokens
    }

    @Test
    fun `an empty script has no words and its end is zero`() {
        for (text in listOf("", "  \n\t ")) {
            val s = Script(text)
            assertEquals(emptyList(), s.tokens)
            assertEquals(emptyList(), s.keys)
            assertEquals(0, s.paragraphStart.size)
            assertEquals(0, s.tokenOfKey(0))
            assertEquals(0, s.firstKeyOfToken(0))
        }
    }

    @Test
    fun `positions outside the script are kept inside it`() {
        val s = Script("Hello, world!")
        assertEquals(0, s.tokenOfKey(-1))
        assertEquals(2, s.tokenOfKey(99))
        assertEquals(0, s.firstKeyOfToken(-1))
        assertEquals(2, s.firstKeyOfToken(99))
    }

    @Test
    fun `spoken words follow the reference rules`() {
        val cases = mapOf(
            "Hello, world!" to "hello world",
            "It’s 21 o'clock" to "its twenty one oclock",
            "100 people" to "100 people",
            "05" to "five",
            "5%" to "five percent",
            "rock'n'roll" to "rocknroll",
            "covid-19" to "covid nineteen",
            "1st" to "1st",
            "—" to "",
            "" to "",
        )
        for ((text, words) in cases) assertEquals(words, Script.toWords(text).joinToString(" "), "toWords(\"$text\")")
    }

    @Test
    fun `the sample script gives exactly one spoken word per shown word`() {
        val s = Script(Samples.SAMPLE_SCRIPT)
        assertEquals(128, s.tokens.size)
        assertEquals(128, s.keys.size)
        assertEquals((0..128).toList(), (0..128).map { s.tokenOfKey(it) })
    }
}
