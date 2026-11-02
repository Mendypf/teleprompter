package io.github.mendypf.prompter.core

/** Scripts the tests read. */
object Samples {

    /**
     * A short script of plain words: every shown word gives exactly one spoken word. It has repeated words ("the",
     * "it", "dough", "times", "percent"), numbers spelled out, and apostrophes. Several tests count on the words at
     * fixed places, and core/tools/make-golden.js reads this text from here, so change them together.
     */
    const val SAMPLE_SCRIPT =
        "When people bake fresh bread, they say it feels easy because you can knead a dough in ten minutes. " +
            "The trouble is, it only gets about eighty percent of the way on its own. To make the final twenty percent, you must " +
            "watch each stage with care to be sure that the dough rises and the crust sets the way it is supposed to. That step " +
            "usually takes five times as long as the first one. I've seen that happen more times than I can count. And if you're " +
            "short on time, you might guess that a hotter oven or a longer rise will fix it. It's not. " +
            "Most bakers won't say what's going wrong, but a good loaf only comes after you give the dough enough time."

    /** A longer script with paragraphs, numbers, a percent, a decimal, both kinds of apostrophe and a dash. */
    val LONG_SCRIPT = """
        Filming a video in one take is easier than it sounds. Most people think you need a studio, a big camera and a crew. You don't. All it takes is the phone in your pocket, a window for light, and a script written the night before.

        Here's the trouble with reading a script on camera. Your eyes leave the lens every few seconds, and the people watching can tell. About 80% of the discarded takes have that one look in them — the glance down to check the next line.

        So put the words right under the lens. The text moves when you talk and it waits when you stop. If you say a word twice, it waits for you. If you skip a line, it catches up on its own.

        The app is small, under 5 MB, because it uses the speech AI that’s already on the phone. It starts in 1.5 seconds. Nothing goes to the internet. The script stays on the phone, and the mic only listens while the camera is open.

        Here's what changes. A video that used to take 6 or 7 takes now takes 1 or 2. That's 3 times faster, and the picture looks like you're talking straight to the viewer.

        If you want to try it, start with a short script of 100 words or less. Read it once out loud, then hit record and just talk. Don't rush the pauses; the words will wait for you.
    """.trimIndent()
}
