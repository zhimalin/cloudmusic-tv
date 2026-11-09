package com.cloudmusic.core.player

import com.cloudmusic.core.network.Lyric

/** 一行解析后的歌词。 */
data class LrcLine(
    /** 歌曲内的绝对时间（毫秒），已应用 `[offset:]` 且不会小于 0。 */
    val timeMs: Long,
    /** 原文；只有时间戳没有文本时为空字符串。 */
    val text: String,
    /** 同一时间戳的翻译；没有则为 null。 */
    val translation: String? = null,
)

/**
 * LRC 歌词解析器（纯 Kotlin，可 JVM 单测）。
 *
 * 支持：
 * - `[mm:ss]`、`[mm:ss.xxx]`（1~3 位小数，`.5` / `.50` / `.500` 都按 500ms 处理）；
 * - 一行多个时间标签：`[00:01.00][00:03.00]同一句`；
 * - `[offset:+500]` / `[offset:-500]`：**加到**所有时间戳上（正值 = 歌词整体延后），
 *   结果小于 0 时夹到 0；
 * - 翻译按**时间戳完全相等**合并（翻译行的时间戳必须和原文一致）；
 * - 输出按 `timeMs` 升序，时间相同的行保持文件里的先后顺序（稳定排序）。
 *
 * 跳过：
 * - 空行、`[ti:]` / `[ar:]` / `[al:]` / `[by:]` / `[length:]` 等非时间标签；
 * - 非法行（不是时间标签开头、秒数 ≥ 60、标签格式不完整）。
 *
 * 保留：
 * - 只有时间戳没有文本的行（`[00:12.000]`）会产出 `text = ""` 的行。这类行通常表示
 *   前奏/间奏的空档，播放页可以据此清空当前行；如果不需要，UI 过滤空文本即可。
 *
 * 翻译行的 offset 规则：翻译自己有 `[offset:]` 就用翻译的；否则继承原文的 offset，
 * 保证同一份歌词的原文/翻译对齐。
 */
object LrcParser {

    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
    private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)

    /** 解析原文 + 可选翻译。原文为空（或没有任何合法时间标签）时返回空列表。 */
    fun parse(text: String?, translation: String? = null): List<LrcLine> {
        val main = parseDocument(text, fallbackOffsetMs = 0L)
        if (main.lines.isEmpty()) return emptyList()

        val translations = HashMap<Long, String>()
        if (!translation.isNullOrBlank()) {
            val translated = parseDocument(translation, fallbackOffsetMs = main.offsetMs)
            for (line in translated.lines) {
                if (line.text.isNotBlank() && line.timeMs !in translations) {
                    translations[line.timeMs] = line.text
                }
            }
        }

        return main.lines
            .sortedBy { it.timeMs }
            .map { LrcLine(timeMs = it.timeMs, text = it.text, translation = translations[it.timeMs]) }
    }

    /** 直接解析 `song/lyric` 接口返回的 [Lyric]（原文 + 中文翻译）。 */
    fun parse(lyric: Lyric): List<LrcLine> = parse(lyric.text, lyric.translation)

    // ------------------------------------------------------------------ 内部实现

    private data class TimedLine(
        val timeMs: Long,
        val text: String,
        /** 文件里的出现顺序，用于相同时间戳的稳定排序。 */
        val order: Int,
    )

    private data class ParsedDocument(
        val lines: List<TimedLine>,
        val offsetMs: Long,
    )

    private fun parseDocument(raw: String?, fallbackOffsetMs: Long): ParsedDocument {
        if (raw.isNullOrBlank()) return ParsedDocument(emptyList(), fallbackOffsetMs)

        val offset = OFFSET_TAG.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: fallbackOffsetMs
        val lines = ArrayList<TimedLine>()
        var order = 0

        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            val matches = TIME_TAG.findAll(line).toList()
            if (matches.isEmpty()) return@forEach // 元数据标签 / 非法行

            // 文本是“最后一个时间标签之后”的部分；标签之间的空白自然被丢掉。
            val text = line.substring(matches.last().range.last + 1).trim()

            for (match in matches) {
                val minutes = match.groupValues[1].toIntOrNull() ?: continue
                val seconds = match.groupValues[2].toIntOrNull() ?: continue
                if (seconds !in 0..59) continue // [00:61.00] 这类非法秒数
                val fraction = match.groupValues[3]
                val millis = if (fraction.isEmpty()) {
                    0
                } else {
                    fraction.padEnd(3, '0').take(3).toInt()
                }
                val timeMs = ((minutes * 60L + seconds) * 1000L + millis + offset).coerceAtLeast(0L)
                lines += TimedLine(timeMs = timeMs, text = text, order = order++)
            }
        }

        return ParsedDocument(lines = lines, offsetMs = offset)
    }
}
