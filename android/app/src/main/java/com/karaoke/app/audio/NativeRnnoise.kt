package com.karaoke.app.audio

import android.util.Log

object NativeRnnoise {
    private const val TAG = "NativeRnnoise"

    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("rnnoise_jni")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Native RNNoise unavailable, will use fallback: ${t.message}")
            false
        }
    }

    external fun createState(): Long
    external fun destroyState(handle: Long)
    external fun processFrame(handle: Long, input: FloatArray, output: FloatArray): Float
}
