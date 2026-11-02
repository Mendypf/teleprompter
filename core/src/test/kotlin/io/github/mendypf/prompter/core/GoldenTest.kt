package io.github.mendypf.prompter.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Checks the app against the reference JavaScript rules (core/tools/reference-matcher.js). golden.txt holds a few
 * hundred heard sentences with the answers those rules gave (made by core/tools/make-golden.js); the app must give
 * every one of them back.
 */
class GoldenTest {

    @Test
    fun `the app hears and follows exactly like the reference rules`() {
        val file = javaClass.getResource("/golden.txt") ?: fail("golden.txt is missing; run core/tools/make-golden.js")
        var script: Script? = null
        var matcher: Matcher? = null
        val scripts = mutableListOf<String>()
        var cases = 0
        var wordChecks = 0
        var expected: List<Int>? = null
        val wrong = mutableListOf<String>()

        for (line in file.readText(Charsets.UTF_8).lines()) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val f = line.split("\t")
            when (f[0]) {
                "near" -> assertEquals(f[1].toInt(), Matcher.NEAR, "NEAR differs from the reference")
                "far" -> assertEquals(f[1].toInt(), Matcher.FAR, "FAR differs from the reference")
                "words" -> {
                    wordChecks++
                    val got = Script.toWords(f[1]).joinToString(" ")
                    if (got != f[2]) wrong += "toWords(\"${f[1]}\") gave \"$got\", the reference \"${f[2]}\""
                }
                "script" -> {
                    scripts += f[1]
                    script = Script(f[1]).also { matcher = Matcher(it.keys) }
                }
                "keys" -> assertEquals(f[1], script!!.keys.joinToString(" "), "the script's spoken words differ")
                "case" -> {
                    cases++
                    val pos = f[1].toInt()
                    val words = Script.toWords(f[2])
                    val lands = matcher!!.advance(pos, words)
                    if (words.joinToString(" ") != f[3]) {
                        wrong += "heard \"${f[2]}\": words ${words.joinToString(" ")}, the reference ${f[3]}"
                    } else if (lands != f[4].toInt()) {
                        wrong += "from $pos hearing \"${f[2]}\": landed $lands, the reference ${f[4]}"
                    }
                }
                "end" -> expected = listOf(f[1].toInt(), f[2].toInt())
                else -> fail("unknown line in golden.txt: $line")
            }
        }

        assertEquals(expected, listOf(cases, wordChecks), "golden.txt looks cut short")
        assertTrue(cases >= 300, "too few cases: $cases")
        assertEquals(Samples.SAMPLE_SCRIPT, scripts.first(), "golden.txt was made from a different script; run core/tools/make-golden.js")
        assertTrue(wrong.isEmpty(), "${wrong.size} differences from the reference:\n" + wrong.take(20).joinToString("\n"))
    }
}
