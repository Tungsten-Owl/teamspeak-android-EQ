cmake_minimum_required(VERSION 3.22.1)
project(ts3rnnoise C)

add_library(ts3rnnoise SHARED
        rnnoise_jni.c
        rnnoise/src/denoise.c
        rnnoise/src/rnn.c
        rnnoise/src/rnn_data.c
        rnnoise/src/pitch.c
        rnnoise/src/kiss_fft.c
        rnnoise/src/celt_lpc.c)

target_include_directories(ts3rnnoise PRIVATE
        rnnoise/include
        rnnoise/src)

target_compile_options(ts3rnnoise PRIVATE
        -O3
        -fvisibility=hidden
        -Wno-unused-parameter
        -Wno-unused-function)

target_link_libraries(ts3rnnoise log m)
