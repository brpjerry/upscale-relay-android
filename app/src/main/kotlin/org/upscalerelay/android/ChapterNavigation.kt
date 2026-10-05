package org.upscalerelay.android

/** What a press of the player's previous/next button does. */
internal sealed interface ChapterStep {
    /** Jump to a chapter mark inside the file. */
    data class Seek(val seconds: Double) : ChapterStep

    /** Back to 0:00 — "previous" with no earlier chapter to go to. */
    data object RestartFile : ChapterStep

    /** "Next" with no later chapter: the file is done. */
    data object FinishFile : ChapterStep

    /** A second "previous" straight after a restart: the file before this one. */
    data object PreviousFile : ChapterStep
}

/** How far into a chapter "previous" restarts it instead of leaving it. */
internal const val CHAPTER_RESTART_THRESHOLD_SECONDS = 3.0

/**
 * Resolves a previous (-1) / next (+1) press. The buttons are always there, so
 * they need an answer at the edges of the file as well as between chapters:
 *
 * - **Next** goes to the next chapter mark; in the last chapter, or in a file
 *   without chapters, it finishes the file.
 * - **Previous** restarts the current chapter when well into it and otherwise
 *   goes to the one before, like every player's back button. In the first
 *   chapter, or in a file without chapters, it restarts the file — and when
 *   files play on automatically, a second press straight after that restart
 *   ([repeatedRestart]) goes to the previous file instead.
 *
 * Only a press that *restarted the file* arms the second-press rule. Stepping
 * back through chapters quickly must stop at the start of the file, not fall
 * through into the previous one.
 */
internal fun resolveChapterStep(
    direction: Int,
    chapterStarts: List<Double>,
    positionSeconds: Double,
    autoPlayNext: Boolean,
    repeatedRestart: Boolean,
): ChapterStep? {
    if (direction == 0) return null
    val current = chapterStarts.indexOfLast { it <= positionSeconds }
    if (direction > 0) {
        val next = chapterStarts.getOrNull(current + 1)
        return if (next != null) ChapterStep.Seek(next) else ChapterStep.FinishFile
    }
    if (current > 0) {
        val intoChapter = positionSeconds - chapterStarts[current]
        return ChapterStep.Seek(
            if (intoChapter > CHAPTER_RESTART_THRESHOLD_SECONDS) chapterStarts[current]
            else chapterStarts[current - 1],
        )
    }
    return if (autoPlayNext && repeatedRestart) ChapterStep.PreviousFile else ChapterStep.RestartFile
}
