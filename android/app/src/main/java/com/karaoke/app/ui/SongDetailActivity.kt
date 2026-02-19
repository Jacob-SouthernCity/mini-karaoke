package com.karaoke.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.karaoke.app.R
import com.karaoke.app.network.ApiService
import com.karaoke.app.network.SongDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SongDetailActivity : AppCompatActivity() {

    private var songId: String = ""
    private var songDetail: SongDetail? = null

    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvError: TextView
    private lateinit var btnSing: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnSeparate: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_song_detail)

        songId = intent.getStringExtra("songId") ?: ""

        tvTitle    = findViewById(R.id.tvTitle)
        tvArtist   = findViewById(R.id.tvArtist)
        tvStatus   = findViewById(R.id.tvStatus)
        tvError    = findViewById(R.id.tvError)
        btnSing    = findViewById(R.id.btnSing)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnSeparate = findViewById(R.id.btnSeparate)
        progressBar = findViewById(R.id.progressBar)

        // Set from intent extras for quick display
        tvTitle.text  = intent.getStringExtra("songTitle") ?: ""
        tvArtist.text = intent.getStringExtra("songArtist") ?: ""

        btnSing.setOnClickListener {
            val intent = Intent(this, SingActivity::class.java).apply {
                putExtra("songId", songId)
                putExtra("songTitle", songDetail?.title ?: "")
            }
            startActivity(intent)
        }

        btnRefresh.setOnClickListener { loadDetail() }

        btnSeparate.setOnClickListener { triggerSeparation() }

        loadDetail()
    }

    private fun loadDetail() {
        progressBar.visibility = View.VISIBLE
        btnSeparate.isEnabled = false
        btnSing.isEnabled = false

        lifecycleScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { ApiService.getSongDetail(songId) }
                songDetail = detail
                updateUI(detail)
            } catch (e: Exception) {
                Toast.makeText(this@SongDetailActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun updateUI(song: SongDetail) {
        tvTitle.text  = song.title
        tvArtist.text = "by ${song.artist}"
        tvStatus.text = "Status: ${song.status}"

        val statusColor = when (song.status) {
            "READY"      -> 0xFF2E7D32.toInt()
            "PROCESSING" -> 0xFFE65100.toInt()
            "FAILED"     -> 0xFFC62828.toInt()
            else         -> 0xFF607D8B.toInt()
        }
        tvStatus.setTextColor(statusColor)

        if (!song.error_message.isNullOrBlank()) {
            tvError.visibility = View.VISIBLE
            tvError.text = "Error: ${song.error_message}"
        } else {
            tvError.visibility = View.GONE
        }

        when (song.status) {
            "READY" -> {
                btnSing.isEnabled = true
                btnSeparate.isEnabled = false
                btnRefresh.isEnabled = false
                btnSeparate.text = "Already Separated"
            }
            "PROCESSING" -> {
                btnSing.isEnabled = false
                btnSeparate.isEnabled = false
                btnRefresh.isEnabled = true
                btnSeparate.text = "Processing..."
            }
            "UPLOADED", "FAILED" -> {
                btnSing.isEnabled = false
                btnSeparate.isEnabled = true
                btnRefresh.isEnabled = true
                btnSeparate.text = if (song.status == "FAILED") "Retry Separation" else "Start Separation"
            }
        }
    }

    private fun triggerSeparation() {
        btnSeparate.isEnabled = false
        btnSeparate.text = "Starting..."
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { ApiService.triggerSeparation(songId) }
                Toast.makeText(this@SongDetailActivity, "Separation started!", Toast.LENGTH_SHORT).show()
                loadDetail()
            } catch (e: Exception) {
                Toast.makeText(this@SongDetailActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                btnSeparate.isEnabled = true
                btnSeparate.text = "Start Separation"
            }
        }
    }
}
