package com.cloudmusic.core.data

import android.content.Context
import com.cloudmusic.core.data.music.RecentPlay
import com.cloudmusic.core.data.music.RecentPlayCodec
import com.cloudmusic.core.data.music.RecentPlayStore

/**
 * 最近播放的 SharedPreferences 实现（不引 Room/DataStore/JSON 库）。
 *
 * 存储格式见 [RecentPlayCodec]：一行一条、TAB 分隔。损坏的行会被 [RecentPlayCodec.decode]
 * 跳过，不会让首页崩掉；最多保留 20 条，展示层只取前 5 条。
 */
class SharedPreferencesRecentPlayStore(context: Context) : RecentPlayStore {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): List<RecentPlay> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull(RecentPlayCodec::decode)
            .take(MAX_ENTRIES)
            .toList()
    }

    override fun record(play: RecentPlay) {
        val updated = (listOf(play) + load().filterNot { it.songId == play.songId })
            .take(MAX_ENTRIES)
        prefs.edit()
            .putString(
                KEY_ENTRIES,
                updated.joinToString("\n") { RecentPlayCodec.encode(it) },
            )
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "cloudmusic_recent_plays"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 20
    }
}
