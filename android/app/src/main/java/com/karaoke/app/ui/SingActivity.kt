package com.karaoke.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.karaoke.app.R
import com.karaoke.app.audio.WavUtils
import com.karaoke.app.network.ApiService
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

class SingActivity : AppCompatActivity() {

    private var songId: String = ""

    private var backingPlayer: ExoPlayer? = null
    private var refVocalPlayer: ExoPlayer? = null

    private var audioRecord: AudioRecord? = null
    private var monitorTrack: AudioTrack? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    private var recordingFile: File? = null

    @Volatile private var voiceMonitorVol = 0f

    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 4

    private val PERMISSION_REQUEST_CODE = 1001
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var tvSongTitle: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var sliderVolume: SeekBar
    private lateinit var sliderRefVocal: SeekBar
    private lateinit var sliderVoiceVol: SeekBar
    private lateinit var tvStatus: TextView
    private lateinit var tvRecordingTime: TextView
    private lateinit var progressSong: ProgressBar
    private lateinit var tvSongTime: TextView
    private lateinit var vuMeter: ProgressBar

    @Volatile private var lastMicRms = 0f

    private var recordingStartMs = 0L
    private var timerJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sing)

        songId = intent.getStringExtra("songId") ?: ""
        val songTitle = intent.getStringExtra("songTitle") ?: "Karaoke"

        tvSongTitle     = findViewById(R.id.tvSongTitle)
        btnStart        = findViewById(R.id.btnStart)
        btnStop         = findViewById(R.id.btnStop)
        sliderVolume    = findViewById(R.id.sliderVolume)
        sliderRefVocal  = findViewById(R.id.sliderRefVocal)
        sliderVoiceVol  = findViewById(R.id.sliderVoiceVol)
        tvStatus        = findViewById(R.id.tvStatus)
        tvRecordingTime = findViewById(R.id.tvRecordingTime)
        progressSong    = findViewById(R.id.progressSong)
        tvSongTime      = findViewById(R.id.tvSongTime)
        vuMeter         = findViewById(R.id.vuMeter)

        tvSongTitle.text = songTitle
        btnStop.isEnabled = false

        sliderVolume.max = 100
        sliderVolume.progress = 80
        sliderVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                backingPlayer?.volume = progress / 100f
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        sliderRefVocal.max = 100
        sliderRefVocal.progress = 0
        sliderRefVocal.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                refVocalPlayer?.volume = progress / 100f
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        sliderVoiceVol.max = 100
        sliderVoiceVol.progress = 0
        sliderVoiceVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                voiceMonitorVol = progress / 100f
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnStart.setOnClickListener { checkPermissionsAndStart() }
        btnStop.setOnClickListener  { stopSinging() }

        requestAudioPermission()
    }

    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required!", Toast.LENGTH_LONG).show()
            btnStart.isEnabled = false
        }
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermission()
            return
        }
        startSinging()
    }

    private fun startSinging() {
        tvStatus.text = "Loading tracks…"
        btnStart.isEnabled = false

        scope.launch {
            try {
                // ── Phase 1: IO work (runs concurrently while nothing is on screen yet) ──
                // Pre-initialise AudioRecord on IO — HAL init takes 50–200 ms.
                // By the time the players finish buffering it will be ready, so we can call
                // ar.startRecording() on the main thread with no extra delay.
                val ar = withContext(Dispatchers.IO) {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE
                    )
                }
                audioRecord = ar
                recordingFile = File(filesDir, "recording_${System.currentTimeMillis()}.wav")

                val backingUrl = ApiService.getBackingUrl(songId)
                val vocalsUrl  = ApiService.getVocalsUrl(songId)

                // ── Phase 2: set up players on main thread ──
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Buffering…"
                    btnStop.isEnabled = true

                    setupPlayers(backingUrl, vocalsUrl, onBothReady = {
                        // ── Phase 3: called on main thread the instant both players call play() ──
                        // ar is already initialised — this call is microseconds, not milliseconds.
                        ar.startRecording()
                        isRecording = true
                        // Only the blocking read loop needs IO; recording has already started.
                        launchReadLoop(ar)
                        startUiUpdater()
                        tvStatus.text = "Singing… 🎤"
                    })
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Error: ${e.message}"
                    btnStart.isEnabled = true
                    btnStop.isEnabled = false
                    Toast.makeText(this@SingActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Prepares both ExoPlayers with [playWhenReady] = false. [onBothReady] is invoked on the
     * main thread the instant both players have buffered enough to begin — at that same call site
     * we also call [AudioRecord.startRecording], giving near-zero offset between mic and music.
     */
    private fun setupPlayers(backingUrl: String, vocalsUrl: String, onBothReady: () -> Unit) {
        var readyFlags = 0
        var started = false

        fun startIfAllReady() {
            if (readyFlags == 0b11 && !started) {
                started = true
                backingPlayer?.play()
                refVocalPlayer?.play()
                onBothReady()   // ar.startRecording() fires here — same call stack, < 1 ms gap
            }
        }

        backingPlayer = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(backingUrl))
            volume = sliderVolume.progress / 100f
            playWhenReady = false
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) { readyFlags = readyFlags or 1; startIfAllReady() }
                    if (state == Player.STATE_ENDED && isRecording) stopSinging()
                }
            })
            prepare()
        }

        refVocalPlayer = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(vocalsUrl))
            volume = sliderRefVocal.progress / 100f
            playWhenReady = false
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) { readyFlags = readyFlags or 2; startIfAllReady() }
                }
                override fun onPlayerError(error: PlaybackException) {
                    refVocalPlayer?.release(); refVocalPlayer = null
                    readyFlags = readyFlags or 2
                    startIfAllReady()
                }
            })
            prepare()
        }
    }

    /**
     * Blocking read loop — runs on IO. AudioRecord is already capturing (startRecording was
     * called on main thread), so the first ar.read() returns real audio with no init delay.
     */
    private fun launchReadLoop(ar: AudioRecord) {
        recordingJob = scope.launch(Dispatchers.IO) {
            // Optional low-latency mic monitor (headphones only, off by default)
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val mt = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            monitorTrack = mt
            mt.play()

            val fos = FileOutputStream(recordingFile!!)
            WavUtils.writeWavHeader(fos, SAMPLE_RATE, 1, 16)

            val buffer = ShortArray(BUFFER_SIZE / 2)
            val monitorBuf = ShortArray(buffer.size)
            while (isRecording) {
                val read = ar.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val bytes = ByteArray(read * 2)
                    var sum = 0.0
                    val vol = voiceMonitorVol
                    for (i in 0 until read) {
                        val s = buffer[i]
                        bytes[i * 2]     = (s.toInt() and 0xFF).toByte()
                        bytes[i * 2 + 1] = (s.toInt() shr 8).toByte()
                        monitorBuf[i] = (s * vol).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        sum += s.toLong() * s
                    }
                    fos.write(bytes)
                    if (vol > 0f) mt.write(monitorBuf, 0, read, AudioTrack.WRITE_NON_BLOCKING)
                    lastMicRms = sqrt(sum / read).toFloat()
                }
            }

            ar.stop(); ar.release(); audioRecord = null
            mt.stop(); mt.release(); monitorTrack = null
            fos.close()
            WavUtils.fixWavHeader(recordingFile!!)
        }
    }

    private fun startUiUpdater() {
        recordingStartMs = System.currentTimeMillis()
        timerJob = scope.launch {
            while (isRecording) {
                val elapsed = (System.currentTimeMillis() - recordingStartMs) / 1000
                tvRecordingTime.text = "%02d:%02d".format(elapsed / 60, elapsed % 60)

                val p = backingPlayer
                if (p != null) {
                    val position = p.currentPosition
                    val duration = p.duration
                    if (duration > 0L) {
                        progressSong.max = (duration / 1000).toInt().coerceAtLeast(1)
                        progressSong.progress = (position / 1000).toInt()
                        tvSongTime.text = "${formatTime(position)} / ${formatTime(duration)}"
                    } else {
                        tvSongTime.text = "${formatTime(position)} / --:--"
                    }
                }

                vuMeter.progress = (lastMicRms / 32768f * 100f).toInt().coerceIn(0, 100)
                delay(100)
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun stopSinging() {
        isRecording = false
        timerJob?.cancel()
        btnStop.isEnabled = false
        btnStart.isEnabled = false
        vuMeter.progress = 0
        tvStatus.text = "Saving recording…"

        backingPlayer?.stop(); backingPlayer?.release(); backingPlayer = null
        refVocalPlayer?.stop(); refVocalPlayer?.release(); refVocalPlayer = null

        scope.launch {
            recordingJob?.join()

            val path = recordingFile?.absolutePath ?: run {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "No recording file!"
                    btnStart.isEnabled = true
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                startActivity(Intent(this@SingActivity, ResultActivity::class.java).apply {
                    putExtra("recordingPath", path)
                    putExtra("sampleRate", SAMPLE_RATE)
                    putExtra("songTitle", tvSongTitle.text.toString())
                    putExtra("songId", songId)
                })
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        backingPlayer?.release()
        refVocalPlayer?.release()
        audioRecord?.release()
        monitorTrack?.release()
        scope.cancel()
    }
}
