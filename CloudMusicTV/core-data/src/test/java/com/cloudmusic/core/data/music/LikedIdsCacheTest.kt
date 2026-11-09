package com.cloudmusic.core.data.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LikedIdsCacheTest {

    @Test
    fun replaceAllAndContains() {
        val cache = LikedIdsCache(maxSize = 10)
        cache.replaceAll(listOf(1, 2, 3, 1984))

        assertTrue(cache.contains(1984))
        assertFalse(cache.contains(99))
        assertEquals(4, cache.size())
    }

    @Test
    fun setLikedAddsAndRemoves() {
        val cache = LikedIdsCache(maxSize = 10)
        cache.replaceAll(listOf(7))

        cache.setLiked(8, true)
        assertTrue(cache.contains(8))
        assertEquals(2, cache.size())

        cache.setLiked(7, false)
        assertFalse(cache.contains(7))
        assertEquals(1, cache.size())
    }

    @Test
    fun capEvictsOldestAndKeepsNewest() {
        val cache = LikedIdsCache(maxSize = 3)
        cache.replaceAll(listOf(1, 2, 3))

        cache.setLiked(4, true)
        assertFalse("最旧的 1 应被淘汰", cache.contains(1))
        assertTrue(cache.contains(2))
        assertTrue(cache.contains(3))
        assertTrue(cache.contains(4))
        assertEquals(3, cache.size())

        cache.setLiked(2, true)
        assertEquals("重复收藏不应增加 size", 3, cache.size())
    }

    @Test
    fun replaceAllOverCapKeepsLastIds() {
        val cache = LikedIdsCache(maxSize = 3)
        cache.replaceAll(listOf(1, 2, 3, 4, 5))

        assertFalse(cache.contains(1))
        assertFalse(cache.contains(2))
        assertTrue(cache.contains(3))
        assertTrue(cache.contains(4))
        assertTrue(cache.contains(5))
        assertEquals(3, cache.size())
    }
}
