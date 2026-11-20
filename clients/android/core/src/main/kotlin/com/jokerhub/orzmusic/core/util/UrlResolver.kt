package com.jokerhub.orzmusic.core.util

object UrlResolver {

    /** 用户可能输入 `host` / `host/dir` / `https://host`;统一补 scheme 和末尾 `/`。 */
    fun normalizeBaseUrl(raw: String, fallback: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return fallback.trimEnd('/') + "/"
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return withScheme.trimEnd('/') + "/"
    }

    /** 后端 `streamUrl` / `rawUrl` 是 `/api/...`;拼到 baseUrl 上。 */
    fun resolve(baseUrl: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }
}
