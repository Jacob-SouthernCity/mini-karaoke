package com.karaoke.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.karaoke.app.R
import com.karaoke.app.network.ApiService
import com.karaoke.app.network.SongListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SongListActivity : AppCompatActivity() {

    private lateinit var searchBox: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SongAdapter
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_song_list)

        searchBox    = findViewById(R.id.searchBox)
        recyclerView = findViewById(R.id.recyclerView)
        statusText   = findViewById(R.id.statusText)

        adapter = SongAdapter { song -> openDetail(song) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<Button>(R.id.btnSearch).setOnClickListener {
            loadSongs(searchBox.text.toString())
        }

        loadSongs()
    }

    private fun loadSongs(query: String = "") {
        statusText.text = "Loading..."
        lifecycleScope.launch {
            try {
                val songs = withContext(Dispatchers.IO) { ApiService.getSongs(query) }
                adapter.submitList(songs)
                statusText.text = if (songs.isEmpty()) "No songs found." else "${songs.size} song(s)"
            } catch (e: Exception) {
                statusText.text = "Error: ${e.message}"
                Toast.makeText(this@SongListActivity, "Failed to load songs: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openDetail(song: SongListItem) {
        val intent = Intent(this, SongDetailActivity::class.java).apply {
            putExtra("songId", song.id)
            putExtra("songTitle", song.title)
            putExtra("songArtist", song.artist)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        loadSongs(searchBox.text.toString())
    }
}

class SongAdapter(
    private val onClick: (SongListItem) -> Unit
) : RecyclerView.Adapter<SongAdapter.VH>() {

    private var items: List<SongListItem> = emptyList()

    fun submitList(list: List<SongListItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        private val title: TextView  = v.findViewById(R.id.tvTitle)
        private val artist: TextView = v.findViewById(R.id.tvArtist)
        private val status: TextView = v.findViewById(R.id.tvStatus)

        fun bind(song: SongListItem) {
            title.text  = song.title
            artist.text = song.artist
            status.text = song.status
            val color = when (song.status) {
                "READY"      -> 0xFF2E7D32.toInt()  // green
                "PROCESSING" -> 0xFFE65100.toInt()  // orange
                "FAILED"     -> 0xFFC62828.toInt()  // red
                else         -> 0xFF607D8B.toInt()  // grey
            }
            status.setTextColor(color)
            itemView.setOnClickListener { onClick(song) }
        }
    }
}
