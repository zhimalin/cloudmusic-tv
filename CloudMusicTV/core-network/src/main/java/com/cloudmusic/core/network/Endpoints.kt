package com.cloudmusic.core.network

/**
 * 真实接口地址与协议常量。
 *
 * 全部对照 `tools/probe/probe.mjs` 移植：请求一律 `POST /weapi/...`，
 * 表单体是 `params=...&encSecKey=...`，业务码在响应 JSON 的 `code` 字段。
 *
 * 路由映射规则和 Node 版一致：`/api/xxx` → `/weapi/xxx`（见 [weapiPath]）。
 * 这里的常量写的是 `/api/...` 形式，方便和 [Endpoints] 之外的文档、Node 版逐条对照。
 */
object Endpoints {

    /** 只访问 this 一个域名；CDN 地址只作为字符串返回，客户端不去下载。 */
    const val BASE_URL: String = "https://music.163.com"

    const val REFERER: String = "https://music.163.com/"

    const val ORIGIN: String = "https://music.163.com"

    /** 与 Node 探针完全一致的 UA。 */
    const val USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    /** 校验登录态，返回 `profile`（昵称 / uid）。 */
    const val ACCOUNT: String = "/api/nuser/account/get"

    /** 每日推荐（需要登录态）；返回 `data.dailySongs`。 */
    const val DAILY_SONGS: String = "/api/v3/discovery/recommend/songs"

    /** 获取播放地址；返回 `data[]`，元素里有 `url` / `br` / `level` / `freeTrialInfo`。 */
    const val SONG_URL_V1: String = "/api/song/enhance/player/url/v1"

    /** 我的歌单 / 用户歌单；返回 `playlist[]`。 */
    const val USER_PLAYLIST: String = "/api/user/playlist"

    /**
     * 歌单详情（v6）。`n=100000&s=8` 时 `playlist.tracks` 会带上完整歌曲对象，
     * `playlist.trackIds` 带全部 id。
     *
     * 注意：文档里常见的 `playlist/track/all` 在 weapi 下返回 `code=404 接口未找到！`，
     * 实测只有这个 v6 详情接口可用，见 [NeteaseClient.playlistTracks]。
     */
    const val PLAYLIST_DETAIL_V6: String = "/api/v6/playlist/detail"

    /** 歌曲详情（v3）；参数 `c` 是 `[{"id":...}, ...]` 的字符串化 JSON。 */
    const val SONG_DETAIL_V3: String = "/api/v3/song/detail"

    /**
     * 「我喜欢的音乐」id 列表。
     *
     * 实测 `/weapi/song/like/list` 和各种变体都返回 404（`接口未找到！`），
     * 所以 [NeteaseClient.likeList] 改走「喜欢歌单（specialType=5）+ 歌单详情」，
     * 结果集和这个接口一致。常量保留下来只为记录协议现状，客户端不会调用它。
     */
    const val SONG_LIKE_LIST_UNAVAILABLE: String = "/api/song/like/list"

    /**
     * 私人 FM。真实返回 `data[]` 一批 3 首，歌曲字段是完整版
     * （`artists` / `album` / `duration`），不是每日推荐的 `ar` / `al` / `dt`。
     */
    const val PERSONAL_FM: String = "/api/v1/radio/get"

    /**
     * 私人 FM「不喜欢」。真实接口要求 `alg=RT&songId=...&time=25` 放在 query，
     * 同时 body 里也带 `songId`。
     */
    const val FM_TRASH: String = "/api/radio/trash/add"

    /**
     * 红心 / 取消红心。路径是 `/api/radio/like`（weapi 映射后为 `/weapi/radio/like`），
     * 不是 `/api/song/like`。额外 cookie `os=pc; appver=2.7.1.198277` 是必须的，
     * 缺了会返回 `code=-460 检测到您的网络环境存在风险`。
     */
    const val LIKE: String = "/api/radio/like"

    /** 歌词；`lv/kv/tv = -1` 时同时返回原文/翻译/罗马音。 */
    const val LYRIC: String = "/api/song/lyric"

    /** Node 版的路由映射：`/api/xxx` → `/weapi/xxx`。 */
    internal fun weapiPath(apiPath: String): String =
        if (apiPath.startsWith("/api")) "/weapi" + apiPath.removePrefix("/api") else apiPath
}

/**
 * `song/url/v1` 的音质档位，取值和接口文档一致。
 *
 * Node 探针里是字符串 `standard|higher|exhigh|lossless`。
 */
enum class SongLevel(val value: String) {
    STANDARD("standard"),
    HIGHER("higher"),
    EXHIGH("exhigh"),
    LOSSLESS("lossless"),
}
