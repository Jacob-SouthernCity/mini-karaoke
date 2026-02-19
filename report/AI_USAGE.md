# AI Usage Report

## Overview
This project was built with AI-assisted pair programming (Claude Code + Codex), but with human-led architecture, prioritization, and acceptance decisions. I used AI as an implementation accelerator, not as a one-shot code generator.

## How I Used AI
- I first gave ChatGPT a detailed product-and-engineering prompt that decomposed the assignment into backend, Android, pitch/voice analysis, and on-device denoising tracks.
- I asked for an implementation order that would produce a working demo early, then iterated feature-by-feature.
- I used Claude Code for rapid implementation loops and Codex for targeted debugging, code inspection, and integration fixes.

## Prompt-Driven Breakdown I Followed
I framed the project in explicit stages, then mapped each stage to concrete files and deliverables.

1. Requirements framing
- Web portal: upload + metadata + separation + status + Android-facing APIs.
- Android: browse/search songs, sing with backing, post-sing analysis, denoise and mix playback.
- Constraints: working end-to-end demo over UI polish.

2. System design selection
- Backend: FastAPI + SQLite + local filesystem.
- Source separation: Demucs CLI subprocess.
- Android audio: ExoPlayer + AudioRecord.
- Pitch: frame-based YIN.
- Voice type: heuristic range matching.
- On-device denoising: RNNoise (JNI/NDK), with fallback path for resilience.

3. Planned implementation order (from my AI planning prompt)
- Step 1: backend skeleton + mock list.
- Step 2: Android skeleton and basic sing/record flow.
- Step 3: real upload/storage/list APIs.
- Step 4: Demucs separation + status flow + backing endpoint.
- Step 5: Android integration with real backend.
- Step 6: Result screen pitch detection + note conversion + voice classification.
- Step 7: RNNoise on-device denoising + vocal/noise mix playback.
- Step 8: README + AI usage report + demo instructions.

4. Iteration strategy
- Keep each step shippable.
- Validate quickly after each integration.
- Only then iterate on sync/latency and UI refinements.

## What Was Actually Implemented
1. Web portal upload + metadata + source separation
- `backend/app/main.py` (`/api/songs/upload`, `/api/songs/{id}/separate`, `/api/songs/{id}/progress`, `/api/songs/{id}/backing`)
- `backend/app/separation.py` (Demucs subprocess wrapper)
- `backend/app/templates/index.html`, `backend/app/templates/song_detail.html`

2. Android search/select + sing with adjustable balance
- `android/app/src/main/java/com/karaoke/app/ui/SongListActivity.kt`
- `android/app/src/main/java/com/karaoke/app/ui/SongDetailActivity.kt`
- `android/app/src/main/java/com/karaoke/app/ui/SingActivity.kt`

3. Post-singing note range + voice type classification
- `android/app/src/main/java/com/karaoke/app/audio/PitchDetector.kt` (YIN, percentile range extraction)
- `android/app/src/main/java/com/karaoke/app/audio/VoiceClassifier.kt` (Bass/Tenor/Alto/Soprano scoring)
- `android/app/src/main/java/com/karaoke/app/ui/ResultActivity.kt`

4. On-device denoising + noise/vocal balance
- `android/app/src/main/java/com/karaoke/app/audio/RNNoise.kt`
- `android/app/src/main/java/com/karaoke/app/audio/NativeRnnoise.kt`
- `android/app/src/main/cpp/` (RNNoise JNI + vendored RNNoise sources)
- fallback: `android/app/src/main/java/com/karaoke/app/audio/SpectralDenoiser.kt`

## Validation and Debugging Approach
- Used AI to inspect API/client contract mismatches and Android activity/manifest launch issues.
- Used staged debugging for native RNNoise integration (missing headers/sources, linker symbols, ABI build path).
- Kept manual device/emulator verification in the loop to confirm end-to-end behavior.

## Current Limitations and Next Improvements
1. Automated validation
- Add backend API tests and Android instrumentation tests for regression safety.

2. Sync calibration robustness
- Current offset is estimated + user-tunable.
- Next step: persist device-route calibration profiles (wired/Bluetooth/speaker).

3. Performance and UX
- Improve RecyclerView updates with `ListAdapter`/`DiffUtil`.
- Add clearer retry/error states around network and separation failures.
