package org.upscalerelay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterNavigationTest {
    private val chapters = listOf(0.0, 90.0, 600.0, 1320.0)

    private fun step(
        direction: Int,
        position: Double,
        starts: List<Double> = chapters,
        autoPlayNext: Boolean = true,
        repeatedRestart: Boolean = false,
    ) = resolveChapterStep(direction, starts, position, autoPlayNext, repeatedRestart)

    @Test
    fun `next walks the chapter marks and finishes the file from the last one`() {
        assertEquals(ChapterStep.Seek(90.0), step(1, 10.0))
        assertEquals(ChapterStep.Seek(1320.0), step(1, 600.0))
        assertEquals(ChapterStep.FinishFile, step(1, 1320.0))
        assertEquals(ChapterStep.FinishFile, step(1, 1400.0, autoPlayNext = false))
    }

    @Test
    fun `previous restarts a chapter it is well into and otherwise goes back one`() {
        assertEquals(ChapterStep.Seek(600.0), step(-1, 700.0))
        assertEquals(ChapterStep.Seek(90.0), step(-1, 601.0))
        assertEquals(ChapterStep.Seek(0.0), step(-1, 91.0))
    }

    @Test
    fun `previous in the first chapter restarts the file`() {
        assertEquals(ChapterStep.RestartFile, step(-1, 45.0))
        assertEquals(ChapterStep.RestartFile, step(-1, 1.0))
        // A first mark that is not at 0:00 still counts as the first chapter,
        // and so does the stretch before it.
        assertEquals(ChapterStep.RestartFile, step(-1, 20.0, starts = listOf(30.0, 90.0)))
        assertEquals(ChapterStep.RestartFile, step(-1, 60.0, starts = listOf(30.0, 90.0)))
    }

    @Test
    fun `a second previous straight after a restart goes to the previous file`() {
        assertEquals(ChapterStep.PreviousFile, step(-1, 0.5, repeatedRestart = true))
        assertEquals(ChapterStep.PreviousFile, step(-1, 0.5, starts = emptyList(), repeatedRestart = true))
        // Not without auto-play: there the file is all there is.
        assertEquals(
            ChapterStep.RestartFile,
            step(-1, 0.5, autoPlayNext = false, repeatedRestart = true),
        )
        // And never from a later chapter, however quick the presses were.
        assertEquals(ChapterStep.Seek(0.0), step(-1, 91.0, repeatedRestart = true))
    }

    @Test
    fun `a file without chapters is one chapter`() {
        assertEquals(ChapterStep.FinishFile, step(1, 300.0, starts = emptyList()))
        assertEquals(ChapterStep.RestartFile, step(-1, 300.0, starts = emptyList()))
        assertNull(step(0, 300.0, starts = emptyList()))
    }
}
