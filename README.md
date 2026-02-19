# mini-karaoke

A full-stack karaoke MVP: Python FastAPI backend + Kotlin Android app.

---

## Project Structure

```
mini-karaoke/
├── backend/                  # Python FastAPI server
│   ├── app/
│   │   ├── main.py           # API routes + web portal
│   │   ├── db.py             # SQLite helpers
│   │   ├── models.py         # Pydantic schemas
│   │   ├── storage.py        # File path helpers
│   │   ├── separation.py     # Demucs subprocess wrapper
│   │   └── templates/        # Jinja2 HTML pages
│   └── requirements.txt
├── android/                  # Android app (Kotlin)
│   └── app/src/main/
│       ├── java/com/karaoke/app/
│       │   ├── ui/           # 4 Activities
│       │   ├── audio/        # PitchDetector, VoiceClassifier, RNNoise, WavUtils
│       │   └── network/      # ApiService (OkHttp)
│       └── res/layout/       # XML layouts
└── report/
    └── AI_USAGE.md
```

> **Not in the repo** (generated locally or machine-specific):
> `backend/venv/`, `backend/storage/`, `backend/karaoke.db`,
> `android/local.properties`, `android/*/build/`

---

## Backend Setup

### Requirements
- Python 3.10+
- Demucs will download its model (~80 MB) on first separation run

### First-time setup

```bash
cd backend
python3.10 -m venv venv
source venv/bin/activate          # Windows: venv\Scripts\activate
pip install -r requirements.txt
```

### Start

```bash
cd backend
source venv/bin/activate
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

| URL | Description |
|-----|-------------|
| http://localhost:8000 | Web portal (upload + manage songs) |
| http://localhost:8000/docs | Auto-generated API docs |
| http://localhost:8000/health | Health check |

### Stop
Press `Ctrl+C`. To kill a backgrounded process: `pkill -f "uvicorn app.main"`

---

## Android Setup

### Requirements
- Android Studio (Hedgehog 2023.1.1 or later)
- Android SDK with API 34
- A device or emulator running Android 8.0+ (API 26+)

### First-time setup

**1. Open the project**

Open the `android/` folder in Android Studio. It will sync Gradle and generate `gradlew`, `local.properties`, and build files automatically.

**2. Configure the backend URL**

Edit `android/app/build.gradle`:

```gradle
// For emulator (default — no change needed):
buildConfigField "String", "BASE_URL", "\"http://10.0.2.2:8000\""

// For physical device on the same Wi-Fi:
buildConfigField "String", "BASE_URL", "\"http://<YOUR_MAC_LAN_IP>:8000\""
```

Find your Mac's LAN IP: `ipconfig getifaddr en0`

**3. Enable microphone on emulator** *(skip for physical device)*

Device Manager → pencil icon on your AVD → Show Advanced Settings →
Microphone → **"Virtual microphone uses host audio input"** → Finish → cold boot.

### Run

Click **▶ Run** in Android Studio (or `Shift+F10`).

---

## App Flow

```
SongListActivity  ──(tap)──►  SongDetailActivity
                                  │  (status = READY)
                                  ▼
                              SingActivity
                              • ExoPlayer: backing track
                              • AudioRecord: mic → WAV
                              • Volume slider (0–100%)
                                  │  (tap Stop)
                                  ▼
                              ResultActivity
                              • Play back raw recording
                              • Lowest / highest note (YIN pitch detection)
                              • Voice type: Bass / Tenor / Alto / Soprano
                              • Noise ←slider→ Clean vocal playback
```

---

## How It Works

### Source separation (Backend)
[Demucs](https://github.com/facebookresearch/demucs) (`htdemucs` model) runs locally via subprocess:
```
python -m demucs --two-stems=vocals <song.mp3>
```
Outputs `no_vocals.wav` (backing track) and `vocals.wav`. 100% local — no external API.

### Pitch detection (Android)
Frame-based **YIN algorithm** (`PitchDetector.kt`):
- 2048-sample frames, 512-sample hop
- Cumulative mean normalized difference function + parabolic interpolation
- Valid range: 80–1000 Hz; silence filtered by RMS threshold
- 10th percentile → lowest note, 90th percentile → highest note

### Voice classification (Android)
Overlap + distance heuristic (`VoiceClassifier.kt`):

| Voice | Range |
|-------|-------|
| Bass | E2–E4 (82–330 Hz) |
| Tenor | C3–C5 (131–523 Hz) |
| Alto | F3–F5 (175–698 Hz) |
| Soprano | C4–C6 (262–1047 Hz) |

Score = `overlap_ratio(IoU) − 0.05 × semitone_center_distance`. Pick highest score.

### On-device denoising (Android)
Primary path: **RNNoise neural denoiser on-device (JNI/C++)** (`RNNoise.kt` + native RNNoise in `app/src/main/cpp/rnnoise/`):
- Input recording is resampled to 48 kHz (RNNoise frame size 480 samples), denoised frame-by-frame, then resampled back.
- `noise.wav = original − vocalClean` (sample-wise residual).
- Result screen mix slider: `alpha × clean + (1−alpha) × noise`.

Fallback path:
- If native RNNoise cannot be loaded (for example missing/failed native lib), app falls back to a local FFT Wiener spectral denoiser (`SpectralDenoiser.kt`) so the flow still works.
