cmake_minimum_required(VERSION 3.22.1)
project(rnnoise_jni)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -fPIC")

add_library(
    rnnoise_jni
    SHARED
    rnnoise_jni.cpp
    rnnoise/denoise.c
    rnnoise/kiss_fft.c
    rnnoise/pitch.c
    rnnoise/celt_lpc.c
    rnnoise/rnn.c
    rnnoise/nnet.c
    rnnoise/nnet_default.c
    rnnoise/parse_lpcnet_weights.c
    rnnoise/rnnoise_tables.c
    rnnoise/rnnoise_data.c
)

target_include_directories(
    rnnoise_jni
    PRIVATE
    rnnoise
)

find_library(log-lib log)

target_link_libraries(
    rnnoise_jni
    ${log-lib}
    m
)
