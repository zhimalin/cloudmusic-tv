package com.cloudmusic.core.player

import com.cloudmusic.core.network.Lyric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** LRC 解析规则；`LrcParser` 的 KDoc 里写的行为都在这里锁死。 */
class LrcParserTest {

    @Test
    fun parsesMultipleTimestampTagsOnOneLine() {
        val lines = LrcParser.parse(
            """
            [00:01.00][00:03.500]同一句
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                LrcLine(timeMs = 1_000, text = "同一句"),
                LrcLine(timeMs = 3_500, text = "同一句"),
            ),
            lines,
        )
    }

    @Test
    fun mergesTranslationByExactTimestamp() {
        val text = """
            [00:01.00]Hello
            [00:02.00]World
            [00:03.00]Instrumental
        """.trimIndent()
        val translation = """
            [00:01.00]你好
            [00:02.00]世界
        """.trimIndent()

        assertEquals(
            listOf(
                LrcLine(1_000, "Hello", "你好"),
                LrcLine(2_000, "World", "世界"),
                LrcLine(3_000, "Instrumental", null),
            ),
            LrcParser.parse(text, translation),
        )
    }

    @Test
    fun appliesPositiveOffsetToAllTimestamps() {
        val lines = LrcParser.parse(
            """
            [offset:+500]
            [00:01.00]A
            [00:02.00]B
            """.trimIndent(),
        )

        assertEquals(listOf(1_500L, 2_500L), lines.map { it.timeMs })
        assertEquals(listOf("A", "B"), lines.map { it.text })
    }

    @Test
    fun appliesNegativeOffsetAndClampsToZero() {
        val lines = LrcParser.parse(
            """
            [offset:-200]
            [00:00.10]A
            [00:01.00]B
            """.trimIndent(),
        )

        assertEquals(
            "负 offset 后时间不能小于 0",
            listOf(0L, 800L),
            lines.map { it.timeMs },
        )
    }

    @Test
    fun translationInheritsMainOffsetWhenItHasNone() {
        val lines = LrcParser.parse(
            text = """
                [offset:+1000]
                [00:01.00]A
            """.trimIndent(),
            translation = """
                [00:01.00]甲
            """.trimIndent(),
        )

        assertEquals(listOf(LrcLine(2_000, "A", "甲")), lines)
    }

    @Test
    fun skipsMetadataAndInvalidLines() {
        val lines = LrcParser.parse(
            """
            [ti:标题]
            [ar:歌手]
            [al:专辑]
            [by:某人]
            [length:03:00]
            这不是时间标签
            [00:61.00]非法秒数
            [00:05.00]正常
            """.trimIndent(),
        )

        assertEquals(listOf(LrcLine(5_000, "正常")), lines)
    }

    @Test
    fun blankInputProducesEmptyList() {
        assertTrue(LrcParser.parse(null).isEmpty())
        assertTrue(LrcParser.parse("").isEmpty())
        assertTrue(LrcParser.parse("   \n\t\n").isEmpty())
        assertTrue(LrcParser.parse("[ti:只有元数据]").isEmpty())
    }

    @Test
    fun keepsTimestampOnlyLinesWithEmptyText() {
        val lines = LrcParser.parse(
            """
            [00:12.000]
            [00:13.00]下一句
            """.trimIndent(),
        )

        assertEquals(
            "只有时间戳的行保留为空文本，UI 可据此清空当前行",
            listOf(
                LrcLine(12_000, ""),
                LrcLine(13_000, "下一句"),
            ),
            lines,
        )
    }

    @Test
    fun supportsOneToThreeFractionDigitsAndNoFraction() {
        val lines = LrcParser.parse(
            """
            [00:01.5]a
            [00:02.50]b
            [00:03.500]c
            [00:04]d
            """.trimIndent(),
        )

        assertEquals(listOf(1_500L, 2_500L, 3_500L, 4_000L), lines.map { it.timeMs })
    }

    @Test
    fun sortsByTimestampButKeepsFileOrderForTies() {
        val lines = LrcParser.parse(
            """
            [00:03.00]c
            [00:01.00]first
            [00:01.00]second
            [00:02.00]b
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                LrcLine(1_000, "first"),
                LrcLine(1_000, "second"),
                LrcLine(2_000, "b"),
                LrcLine(3_000, "c"),
            ),
            lines,
        )
    }

    @Test
    fun parsesLyricModelDirectly() {
        val lyric = Lyric(
            songId = 42,
            text = "[00:01.00]Original",
            translation = "[00:01.00]翻译",
        )

        assertEquals(listOf(LrcLine(1_000, "Original", "翻译")), LrcParser.parse(lyric))
    }
}
