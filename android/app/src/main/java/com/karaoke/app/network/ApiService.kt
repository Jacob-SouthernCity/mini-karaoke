package com.karaoke.app.network

import com.karaoke.app.BuildConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class SongListItem(
    val id: String,
    val title: String,
    val artist: String,
    val status: String
)

data class SongDetail(
    val id: String,
    val title: String,
    val artist: String,
    val status: String,
    val filename_original: String,
    val created_at: String,
    val error_message: String?,
    val backing_path: String?,
    val vocals_path: String?
)

object ApiService {
    val BASE_URL = BuildConfig.BASE_URL

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun getSongs(query: String = ""): List<SongListItem> {
        val url = "$BASE_URL/api/songs?query=${query}"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: "[]"
            val type = object : TypeToken<List<SongListItem>>() {}.type
            return gson.fromJson(body, type)
        }
    }

    fun getSongDetail(songId: String): SongDetail {
        val url = "$BASE_URL/api/songs/$songId"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: throw Exception("Empty response")
            return gson.fromJson(body, SongDetail::class.java)
        }
    }

    fun triggerSeparation(songId: String): String {
        val url = "$BASE_URL/api/songs/$songId/separate"
        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            return body
        }
    }

    fun getBackingUrl(songId: String): String {
        return "$BASE_URL/api/songs/$songId/backing"
    }

    fun getVocalsUrl(songId: String): String {
        return "$BASE_URL/api/songs/$songId/vocals"
    }
}
