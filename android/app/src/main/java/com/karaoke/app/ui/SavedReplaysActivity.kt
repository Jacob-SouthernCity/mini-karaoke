package com.karaoke.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.karaoke.app.R
import com.karaoke.app.replay.SavedReplay
import com.karaoke.app.replay.SavedReplayStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SavedReplaysActivity : AppCompatActivity() {

    private lateinit var rvReplays: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvSubTitle: TextView
    private lateinit var adapter: SavedReplayAdapter

    private val items = mutableListOf<SavedReplay>()
    private var filterSongId: String? = null
    private var filterSongTitle: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_replays)

        rvReplays = findViewById(R.id.rvReplays)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvSubTitle = findViewById(R.id.tvSubTitle)

        filterSongId = intent.getStringExtra("filterSongId")
        filterSongTitle = intent.getStringExtra("filterSongTitle")

        if (!filterSongId.isNullOrBlank()) {
            tvSubTitle.text = "Showing saved replays for ${filterSongTitle ?: "this song"} (long press deletes replay + recording)"
        } else {
            tvSubTitle.text = "Tap to open. Long press to delete replay + local recording."
        }

        adapter = SavedReplayAdapter(
            onClick = { openReplay(it) },
            onLongClick = { deleteReplay(it) }
        )
        rvReplays.layoutManager = LinearLayoutManager(this)
        rvReplays.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items.clear()
        val all = SavedReplayStore.list(this)
        items.addAll(
            if (filterSongId.isNullOrBlank()) all
            else all.filter { it.songId == filterSongId }
        )

        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rvReplays.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (items.isEmpty()) {
            tvEmpty.text = if (filterSongId.isNullOrBlank()) {
                "No saved replays yet. Adjust a replay, then leave the result page to save."
            } else {
                "No saved replays for this song yet."
            }
        }

        adapter.submit(items)
    }

    private fun openReplay(item: SavedReplay) {
        if (!File(item.recordingPath).exists()) {
            Toast.makeText(this, "Recording file no longer exists", Toast.LENGTH_LONG).show()
            SavedReplayStore.delete(this, item.id)
            refresh()
            return
        }

        startActivity(Intent(this, ResultActivity::class.java).apply {
            putExtra("recordingPath", item.recordingPath)
            putExtra("sampleRate", item.sampleRate)
            putExtra("songId", item.songId)
            putExtra("songTitle", item.songTitle)
            putExtra("fromSavedReplay", true)
            putExtra("presetMix", item.mix)
            putExtra("presetVocalVol", item.vocalVol)
            putExtra("presetBackingVol", item.backingVol)
            putExtra("presetSyncOffset", item.syncOffset)
            putExtra("presetManualSync", item.manualSync)
        })
    }

    private fun deleteReplay(item: SavedReplay) {
        val recordingFile = File(item.recordingPath)
        val recordingDeleted = !recordingFile.exists() || recordingFile.delete()
        SavedReplayStore.delete(this, item.id)
        val msg = if (recordingDeleted) {
            "Deleted replay and recording"
        } else {
            "Deleted replay (recording file could not be removed)"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        refresh()
    }
}

private class SavedReplayAdapter(
    private val onClick: (SavedReplay) -> Unit,
    private val onLongClick: (SavedReplay) -> Unit
) : RecyclerView.Adapter<SavedReplayAdapter.VH>() {

    private val items = mutableListOf<SavedReplay>()
    private val timeFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    fun submit(list: List<SavedReplay>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_saved_replay, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], timeFmt, onClick, onLongClick)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSong = itemView.findViewById<TextView>(R.id.tvReplaySong)
        private val tvTime = itemView.findViewById<TextView>(R.id.tvReplayTime)
        private val tvSettings = itemView.findViewById<TextView>(R.id.tvReplaySettings)

        fun bind(
            item: SavedReplay,
            timeFmt: SimpleDateFormat,
            onClick: (SavedReplay) -> Unit,
            onLongClick: (SavedReplay) -> Unit
        ) {
            tvSong.text = item.songTitle
            tvTime.text = timeFmt.format(Date(item.createdAtMs))
            tvSettings.text = "Mix ${item.mix}%   Vocal ${item.vocalVol}%   Backing ${item.backingVol}%" +
                "\nSync ${item.syncOffset} ms (${if (item.manualSync) "manual" else "auto"})"

            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }
    }
}
