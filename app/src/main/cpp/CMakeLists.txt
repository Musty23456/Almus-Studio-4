cmake_minimum_required(VERSION 3.22.1)
project(almus_audio)

find_package(oboe REQUIRED CONFIG)

add_library(almus_audio SHARED
    jni_bridge.cpp
    audio_engine.cpp
    wav_file.cpp
)

target_include_directories(almus_audio PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(almus_audio
    oboe::oboe
    android
    log
)

target_compile_options(almus_audio PRIVATE -Wall -Wextra -O2)
