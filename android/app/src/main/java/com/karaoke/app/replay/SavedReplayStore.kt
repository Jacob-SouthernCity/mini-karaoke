package com.karaoke.app.replay

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class SavedReplay(
    val id: String,
    val songId: String,
    val songTitle: String,
    val recordingPath: String,
    val sampleRate: Int,
    val mix: Int,
    val vocalVol: Int,
    val backingVol: Int,
    val syncOffset: Int,
    val manualSync: Boolean,
    val createdAtMs: Long
)

object SavedReplayStore {
    private const val PREFS = "saved_replays"
    private const val KEY_ITEMS = "items"
    private val gson = Gson()

    fun list(context: Context): List<SavedReplay> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        val type = object : TypeToken<List<SavedReplay>>() {}.type
        return try {
            gson.fromJson<List<SavedReplay>>(json, type)?.sortedByDescending { it.createdAtMs } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, replay: SavedReplay) {
        val all = list(context).toMutableList()
        all.removeAll { it.id == replay.id }
        all.add(0, replay)
        persist(context, all)
    }

    fun upsertForRecording(
        context: Context,
        songId: String,
        songTitle: String,
        recordingPath: String,
        sampleRate: Int,
        mix: Int,
        vocalVol: Int,
        backingVol: Int,
        syncOffset: Int,
        manualSync: Boolean
    ): SavedReplay {
        val existing = list(context).firstOrNull { it.songId == songId && it.recordingPath == recordingPath }
        val replay = SavedReplay(
            id = existing?.id ?: UUID.randomUUID().toString(),
            songId = songId,
            songTitle = songTitle,
            recordingPath = recordingPath,
            sampleRate = sampleRate,
            mix = mix,
            vocalVol = vocalVol,
            backingVol = backingVol,
            syncOffset = syncOffset,
            manualSync = manualSync,
            createdAtMs = System.currentTimeMillis()
        )
        save(context, replay)
        return replay
    }

    fun delete(context: Context, id: String) {
        val all = list(context).filterNot { it.id == id }
        persist(context, all)
    }

    private fun persist(context: Context, items: List<SavedReplay>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ITEMS, gson.toJson(items)).apply()
    }
}
