package com.cloudmusic.core.data

/**
 * Placeholder key/value persistence.
 *
 * TODO(next round): back this with DataStore (preferred) or SharedPreferences.
 * 2GB-RAM TV boxes: keep the cache bounded on disk.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}
