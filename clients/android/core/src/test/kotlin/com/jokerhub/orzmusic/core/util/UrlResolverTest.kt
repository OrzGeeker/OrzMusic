package com.jokerhub.orzmusic.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlResolverTest {

    @Test
    fun normalizeAddsSchemeAndTrailingSlash() {
        assertEquals("https://music.example.com/", UrlResolver.normalizeBaseUrl("music.example.com", "https://fallback"))
        assertEquals("https://music.example.com/", UrlResolver.normalizeBaseUrl("https://music.example.com", "https://fallback"))
        assertEquals("https://fallback/", UrlResolver.normalizeBaseUrl("  ", "https://fallback"))
    }

    @Test
    fun resolveJoinsRelativePaths() {
        assertEquals(
            "https://music.example.com/api/songs/1/stream",
            UrlResolver.resolve("https://music.example.com/", "/api/songs/1/stream")
        )
        assertEquals(
            "https://music.example.com/api/songs/1/stream",
            UrlResolver.resolve("https://music.example.com", "api/songs/1/stream")
        )
    }

    @Test
    fun resolveKeepsAbsoluteUrls() {
        assertEquals(
            "https://cdn.example.com/x.mod",
            UrlResolver.resolve("https://music.example.com/", "https://cdn.example.com/x.mod")
        )
    }
}
