package com.karaoke.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.karaoke.app.R
import com.karaoke.app.audio.PitchDetector
import com.karaoke.app.audio.RNNoise
import com.karaoke.app.audio.VoiceClassifier
import com.karaoke.app.audio.WavUtils
import com.karaoke.app.network.ApiService
import kotlinx.coroutines.*
import java.io.File

class ResultActivity : AppCompatActivity() {

    private var recordingPath: String = ""
    private var sampleRate: Int = 44100
    private var songId: String = ""

    // Denoising output — computed once, used by karaoke playback in real time
    private var originalSamples: ShortArray? = null
    private var vocalCleanSamples: ShortArray? = null
    private var noiseSamples: ShortArray? = null

    // Raw recording playback
    private var rawPlayer: MediaPlayer? = null

    // Karaoke replay: AudioTrack (denoised voice) + ExoPlayer (backing), time-offset synced
    private var karaokeVoiceJob: Job? = null
    private var karaokeVoiceTrack: AudioTrack? = null
    private var karaokeBackingPlayer: ExoPlayer? = null
    private var karaokeBackingStartJob: Job? = null  // holds the delayed backing-start coroutine
    private var karaokeStarted = false

    // Measured once at startup: output latency + input latency (ms).
    // This is the amount of time the voice WAV is "ahead" of where the backing should start.
    private var measuredSyncOffsetMs = 0

    // Views
    private lateinit var tvSongTitle: TextView
    private lateinit var tvRecordingInfo: TextView
    private lateinit var tvLowestNote: TextView
    private lateinit var tvHighestNote: TextView
    private lateinit var tvVoiceType: TextView
    private lateinit var tvExplanation: TextView
    private lateinit var tvDenoiseStatus: TextView
    private lateinit var tvAnalysisStatus: TextView
    private lateinit var tvSyncOffsetMs: TextView
    private lateinit var switchManualSync: Switch
    private lateinit var sliderMix: SeekBar
    private lateinit var sliderVocalVol: SeekBar
    private lateinit var sliderBackingVol: SeekBar
    private lateinit var sliderSyncOffset: SeekBar
    private lateinit var btnPlayRaw: Button
    private lateinit var btnStopRaw: Button
    private lateinit var btnPlayKaraoke: Button
    private lateinit var btnStopKaraoke: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        recordingPath = intent.getStringExtra("recordingPath") ?: ""
        sampleRate    = intent.getIntExtra("sampleRate", 44100)
        songId        = intent.getStringExtra("songId") ?: ""
        val songTitle = intent.getStringExtra("songTitle") ?: ""

        tvSongTitle      = findViewById(R.id.tvSongTitle)
        tvRecordingInfo  = findViewById(R.id.tvRecordingInfo)
        tvLowestNote     = findViewById(R.id.tvLowestNote)
        tvHighestNote    = findViewById(R.id.tvHighestNote)
        tvVoiceType      = findViewById(R.id.tvVoiceType)
        tvExplanation    = findViewById(R.id.tvExplanation)
        tvDenoiseStatus  = findViewById(R.id.tvDenoiseStatus)
        tvAnalysisStatus = findViewById(R.id.tvAnalysisStatus)
        tvSyncOffsetMs   = findViewById(R.id.tvSyncOffsetMs)
        switchManualSync = findViewById(R.id.switchManualSync)
        sliderMix        = findViewById(R.id.sliderMix)
        sliderVocalVol   = findViewById(R.id.sliderVocalVol)
        sliderBackingVol = findViewById(R.id.sliderBackingVol)
        sliderSyncOffset = findViewById(R.id.sliderSyncOffset)
        btnPlayRaw       = findViewById(R.id.btnPlayRaw)
        btnStopRaw       = findViewById(R.id.btnStopRaw)
        btnPlayKaraoke   = findViewById(R.id.btnPlayKaraoke)
        btnStopKaraoke   = findViewById(R.id.btnStopKaraoke)
        progressBar      = findViewById(R.id.progressBar)

        tvSongTitle.text = songTitle

        sliderMix.max = 100
        sliderMix.progress = 80

        sliderVocalVol.max = 100
        sliderVocalVol.progress = 100

        sliderBackingVol.max = 100; sliderBackingVol.progress = 80

        // Sync offset slider: 0–500 ms. Default is set once measurement completes.
        sliderSyncOffset.max = 500
        sliderSyncOffset.isEnabled = false
        switchManualSync.isChecked = false
        switchManualSync.setOnCheckedChangeListener { _, enabled ->
            sliderSyncOffset.isEnabled = enabled
        }
        sliderSyncOffset.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvSyncOffsetMs.text = "${p} ms"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        sliderBackingVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                karaokeBackingPlayer?.volume = p / 100f
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnPlayKaraoke.isEnabled = false
        btnStopKaraoke.isEnabled = false
        btnStopRaw.isEnabled = false

        btnPlayRaw.setOnClickListener     { playRawRecording() }
        btnStopRaw.setOnClickListener     { stopRawPlayback() }
        btnPlayKaraoke.setOnClickListener { startKaraokePlayback() }
        btnStopKaraoke.setOnClickListener { stopKaraokePlayback() }

        // Measure latency offset on a background thread so it doesn't block the UI.
        // AudioTrack creation takes ~10 ms; store result before the user can press Play.
        lifecycleScope.launch(Dispatchers.IO) {
            val ms = measureSyncOffsetMs()
            withContext(Dispatchers.Main) {
                measuredSyncOffsetMs = ms
                sliderSyncOffset.progress = ms
                tvSyncOffsetMs.text = "${ms} ms"
            }
        }

        showRecordingInfo()
        analyseRecording()
    }

    // ── Raw recording playback ────────────────────────────────────────────────

    private fun showRecordingInfo() {
        val file = File(recordingPath)
        if (!file.exists()) {
            tvRecordingInfo.text = "Recording file not found: $recordingPath"
            tvRecordingInfo.setTextColor(0xFFCC0000.toInt())
            btnPlayRaw.isEnabled = false
            return
        }
        val kb = file.length() / 1024
        val durationSec = ((file.length() - 44) / 2.0 / sampleRate).toInt()
        tvRecordingInfo.text = "Recording: ${kb} KB, ~${durationSec}s at ${sampleRate} Hz"
        if (kb < 10) {
            tvRecordingInfo.text = tvRecordingInfo.text.toString() +
                " ⚠️ File very small — mic may not be working"
            tvRecordingInfo.setTextColor(0xFFCC6600.toInt())
        }
    }

    private fun playRawRecording() {
        val file = File(recordingPath)
        if (!file.exists()) { Toast.makeText(this, "No recording file", Toast.LENGTH_SHORT).show(); return }
        stopRawPlayback()
        try {
            rawPlayer = MediaPlayer().apply {
                setAudioAttributes(mediaAudioAttributes())
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener { stopRawPlayback() }
            }
            btnPlayRaw.isEnabled = false; btnStopRaw.isEnabled = true
        } catch (e: Exception) {
            Toast.makeText(this, "Playback error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRawPlayback() {
        rawPlayer?.stop(); rawPlayer?.release(); rawPlayer = null
        btnPlayRaw.isEnabled = true; btnStopRaw.isEnabled = false
    }

    // ── Pitch + voice analysis ────────────────────────────────────────────────

    private fun analyseRecording() {
        progressBar.visibility = View.VISIBLE
        tvAnalysisStatus.text = "Step 1/3: Reading recording…"

        lifecycleScope.launch {
            val file = File(recordingPath)
            if (!file.exists()) {
                tvAnalysisStatus.text = "Error: Recording file not found"
                progressBar.visibility = View.GONE
                return@launch
            }

            val samples = withContext(Dispatchers.IO) { WavUtils.readPcmSamples(file) }
            originalSamples = samples

            if (samples.size < sampleRate) {
                tvAnalysisStatus.text = "Recording too short (< 1 second). Try singing longer."
                progressBar.visibility = View.GONE
                return@launch
            }

            tvAnalysisStatus.text = "Step 2/3: Detecting pitch (YIN)…"
            val pitchResult = withContext(Dispatchers.Default) {
                PitchDetector.analyse(samples, sampleRate)
            }

            if (pitchResult == null) {
                tvLowestNote.text = "Could not detect pitch"
                tvVoiceType.text  = "No voiced frames found"
                tvExplanation.text = "Check the mic is working (see recording size above). " +
                    "On the emulator enable microphone in AVD settings."
            } else {
                tvLowestNote.text  = "Lowest:  ${pitchResult.lowestNote}"
                tvHighestNote.text = "Highest: ${pitchResult.highestNote}"
                val cls = VoiceClassifier.classify(pitchResult.lowestHz, pitchResult.highestHz)
                tvVoiceType.text   = "Voice type: ${cls.voiceType}"
                tvExplanation.text = cls.explanation
            }

            tvAnalysisStatus.text = "Step 3/3: Denoising…"
            tvDenoiseStatus.text = "Running on-device RNNoise denoiser…"

            val vocalClean = withContext(Dispatchers.Default) { RNNoise.denoise(file, sampleRate) }
            vocalCleanSamples = vocalClean
            val noise = withContext(Dispatchers.Default) { WavUtils.subtractSamples(samples, vocalClean) }
            noiseSamples = noise

            tvDenoiseStatus.text = "Ready. Adjust slider: ◀ Noise  |  Clean Vocal ▶"
            btnPlayKaraoke.isEnabled = true
            tvAnalysisStatus.text = "Analysis complete"
            progressBar.visibility = View.GONE
        }
    }

    // ── Karaoke replay: denoised voice + backing, time-offset corrected ───────

    /**
     * Why the backing is delayed rather than started simultaneously:
     *
     * During RECORDING, the backing had audio output latency Lo before reaching the singer's
     * ears, and the microphone had input latency Li before capturing the singer's voice.
     * So the singing in the WAV file is offset by (Lo + Li) ms relative to the backing
     * track timeline. During REPLAY, both the voice AudioTrack and backing ExoPlayer share
     * the same output latency, so that cancels out. The remaining offset is (Lo + Li) ms.
     *
     * Fix: start voice immediately (WAV pos 0), delay backing by (Lo + Li) ms.
     * Both then arrive at the listener's ear at the correct relative time.
     *
     * The slider lets users fine-tune per device, since hardware latency varies widely.
     */
    private fun startKaraokePlayback() {
        val clean = vocalCleanSamples ?: return
        val noise = noiseSamples ?: return
        if (songId.isEmpty()) {
            Toast.makeText(this, "No song ID — cannot load backing track", Toast.LENGTH_SHORT).show()
            return
        }

        stopKaraokePlayback()
        karaokeStarted = false
        btnPlayKaraoke.isEnabled = false
        btnStopKaraoke.isEnabled = true

        lifecycleScope.launch {
            try {
                val backingUrl = ApiService.getBackingUrl(songId)

                val ep = ExoPlayer.Builder(this@ResultActivity).build()
                ep.setMediaItem(MediaItem.fromUri(backingUrl))
                ep.volume = sliderBackingVol.progress / 100f
                ep.playWhenReady = false
                ep.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && !karaokeStarted) {
                            karaokeStarted = true

                            // Voice starts immediately from WAV position 0.
                            startKaraokeVoiceTrack(clean, noise)

                            // Backing is delayed by the measured sync offset so that the
                            // baked-in recording latency is compensated.
                            val delayMs = if (switchManualSync.isChecked) {
                                sliderSyncOffset.progress.toLong()
                            } else {
                                measuredSyncOffsetMs.toLong()
                            }
                            karaokeBackingStartJob = lifecycleScope.launch {
                                delay(delayMs)
                                if (karaokeStarted) ep.play()
                            }
                        }
                        if (state == Player.STATE_ENDED) stopKaraokePlayback()
                    }
                })
                ep.prepare()
                karaokeBackingPlayer = ep

            } catch (e: Exception) {
                Toast.makeText(this@ResultActivity,
                    "Failed to load backing track: ${e.message}", Toast.LENGTH_LONG).show()
                btnPlayKaraoke.isEnabled = true; btnStopKaraoke.isEnabled = false
            }
        }
    }

    /**
     * Streams denoised voice through AudioTrack. The noise/vocal mix ratio and voice volume
     * are sampled from sliders every ~93 ms chunk for live adjustment.
     * The first chunk is written synchronously before the IO coroutine launches so there is
     * no silent gap at the start of playback.
     */
    private fun startKaraokeVoiceTrack(clean: ShortArray, noise: ShortArray) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val at = AudioTrack.Builder()
            .setAudioAttributes(mediaAudioAttributes())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        karaokeVoiceTrack = at
        at.play()

        val firstCount = minOf(4096, clean.size)
        at.write(buildVoiceChunk(clean, noise, 0, firstCount), 0, firstCount)

        karaokeVoiceJob = lifecycleScope.launch(Dispatchers.IO) {
            var pos = firstCount
            while (pos < clean.size && isActive) {
                val count = minOf(4096, clean.size - pos)
                val written = at.write(buildVoiceChunk(clean, noise, pos, count), 0, count)
                if (written < 0) break
                pos += count
            }
            withContext(Dispatchers.Main) {
                if (isActive && karaokeStarted) stopKaraokePlayback()
            }
        }
    }

    private fun buildVoiceChunk(clean: ShortArray, noise: ShortArray, start: Int, count: Int): ShortArray {
        val cleanRatio = sliderMix.progress / 100f
        val vocalVol = sliderVocalVol.progress / 100f
        return ShortArray(count) { i ->
            val c = clean[start + i] * cleanRatio * vocalVol
            val n = noise[start + i] * (1f - cleanRatio) * vocalVol
            (c + n).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun stopKaraokePlayback() {
        karaokeBackingStartJob?.cancel(); karaokeBackingStartJob = null
        karaokeVoiceJob?.cancel(); karaokeVoiceJob = null
        try { karaokeVoiceTrack?.stop() } catch (_: Exception) {}
        karaokeVoiceTrack?.release(); karaokeVoiceTrack = null
        try { karaokeBackingPlayer?.stop() } catch (_: Exception) {}
        karaokeBackingPlayer?.release(); karaokeBackingPlayer = null
        karaokeStarted = false
        btnPlayKaraoke.isEnabled = vocalCleanSamples != null
        btnStopKaraoke.isEnabled = false
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Estimates the total recording-to-playback sync offset in milliseconds:
     *   offset = output_latency (Lo) + input_latency (Li)
     *
     * Lo is queried via the hidden AudioTrack.getLatency() — this reflects the full pipeline
     * from AudioFlinger to the speaker, which is what ExoPlayer also goes through.
     * Li is estimated from AudioRecord's minimum buffer size (the hardware capture buffer).
     *
     * Typical values: speaker 100–250 ms, wired headphones 50–100 ms, BT 150–400 ms.
     * The slider lets users override this if the automatic value is off for their device.
     */
    private fun measureSyncOffsetMs(): Int {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)

        val outputLatencyMs: Int = try {
            val testTrack = AudioTrack.Builder()
                .setAudioAttributes(mediaAudioAttributes())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            @Suppress("DiscouragedPrivateApi")
            val method = AudioTrack::class.java.getDeclaredMethod("getLatency")
            method.isAccessible = true
            val ms = method.invoke(testTrack) as Int
            testTrack.release()
            ms
        } catch (e: Exception) {
            // Fallback: 3 hardware buffers at PROPERTY_OUTPUT_FRAMES_PER_BUFFER
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val frames = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
                ?.toIntOrNull() ?: 512
            frames * 3 * 1000 / sampleRate
        }

        // Input latency: hardware capture buffer = minInputBuf bytes / (sampleRate * 2 bytes/sample)
        val inputMinBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val inputLatencyMs = inputMinBuf * 1000 / (sampleRate * 2)

        return (outputLatencyMs + inputLatencyMs).coerceIn(50, 500)
    }

    private fun mediaAudioAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    override fun onDestroy() {
        super.onDestroy()
        stopRawPlayback()
        stopKaraokePlayback()
    }
}
