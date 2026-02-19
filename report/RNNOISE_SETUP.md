# RNNoise On-Device Setup

This project now prefers native RNNoise (JNI) for on-device denoising.
If native load fails, it falls back to the Kotlin spectral denoiser.

## 1) Use official pre-trained RNNoise weights (no training required)

```bash
git clone https://github.com/xiph/rnnoise.git
cd rnnoise
./autogen.sh
./configure
make
```

`autogen.sh` downloads model files automatically (per upstream README).

## 2) Train your own RNNoise model (optional)

RNNoise training expects **48 kHz**, **16-bit PCM raw** inputs.

### Generate features

```bash
./dump_features speech.pcm background_noise.pcm foreground_noise.pcm features.f32 200000
```

Optional with RIR augmentation:

```bash
./dump_features -rir_list rir_list.txt speech.pcm background_noise.pcm foreground_noise.pcm features.f32 200000
```

### Train

```bash
python3 train_rnnoise.py features.f32 output_directory --epochs 50
```

### Export C weights

```bash
python3 dump_rnnoise_weights.py --quantize output_directory/rnnoise_50.pth rnnoise_c
```

This generates `rnnoise_data.c` and `rnnoise_data.h` in `rnnoise_c/`.

## 3) Integrate custom weights in this app

Replace these files:

- `android/app/src/main/cpp/rnnoise/rnnoise_data.c`
- `android/app/src/main/cpp/rnnoise/rnnoise_data.h`

Then rebuild Android app.

## Notes

- App recording is 44.1 kHz; RNNoise runs internally at 48 kHz.
- `RNNoise.kt` resamples to/from 48 kHz around native processing.
- For interview/demo, pre-trained weights are enough; training is optional.
