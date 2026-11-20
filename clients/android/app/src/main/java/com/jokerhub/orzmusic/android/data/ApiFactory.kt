package com.jokerhub.orzmusic.android.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jokerhub.orzmusic.core.util.UrlResolver
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/** 默认服务器(官方实例)。设置页可改成自建地址。 */
const val DEFAULT_SERVER_URL = "https://music.jokerhub.cn"

object ApiFactory {

    private val json = Json {
        ignoreUnknownKeys = true   // 后端加字段不炸旧客户端
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun json(): Json = json

    fun create(baseUrl: String): OrzMusicApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        return Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OrzMusicApi::class.java)
    }

    /** Retrofit 要求 baseUrl 以 `/` 结尾;用户输入 `host/dir` 也要补齐。 */
    fun normalizeBaseUrl(raw: String): String =
        UrlResolver.normalizeBaseUrl(raw, DEFAULT_SERVER_URL)
}
