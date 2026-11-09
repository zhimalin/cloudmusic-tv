package com.cloudmusic.core.data.music

/**
 * 最近播放的纯 Kotlin 行编解码，SharedPreferences 实现只负责读写字符串。
 *
 * 一行一条，字段用 TAB 分隔；写入前把字段里的 TAB/换行替换为空格。
 * 解码遇到字段数不对或数字非法时返回 null（由调用方跳过该行），不抛异常。
 */
internal object RecentPlayCodec {

    private const val FIELD_COUNT = 7

    fun encode(play: RecentPlay): String = listOf(
        play.songId.toString(),
        sanitize(play.title),
        sanitize(play.artist),
        sanitize(play.album),
        sanitize(play.coverUrl.orEmpty()),
        play.durationMs.toString(),
        play.playedAtMs.toString(),
    ).joinToString("\t")

    fun decode(line: String): RecentPlay? {
        if (line.isBlank()) return null
        val fields = line.split('\t')
        if (fields.size != FIELD_COUNT) return null
        val songId = fields[0].toLongOrNull() ?: return null
        val durationMs = fields[5].toLongOrNull() ?: return null
        val playedAtMs = fields[6].toLongOrNull() ?: return null
        return RecentPlay(
            songId = songId,
            title = fields[1],
            artist = fields[2],
            album = fields[3],
            coverUrl = fields[4].ifBlank { null },
            durationMs = durationMs,
            playedAtMs = playedAtMs,
        )
    }

    private fun sanitize(value: String): String =
        value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
