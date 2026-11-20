cmake_minimum_required(VERSION 3.22)
project(orzaudio LANGUAGES C CXX)

# 这个工程由 tools/build-orzaudio-android.sh 调用,用 NDK 交叉编译:
#   -DOPENMPT_LIB=<libopenmpt.a 路径>
#   -DANDROID_ABI=arm64-v8a | armeabi-v7a
# 产物: liborzaudio.so(复制到 app/src/main/jniLibs/<abi>/)

set(ORZ_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../orzaudio/Sources/OrzAudioKitCXX")
set(OPENMPT_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libopenmpt/include")

if(NOT DEFINED OPENMPT_LIB)
    message(FATAL_ERROR "OPENMPT_LIB 未指定 —— 用 tools/build-orzaudio-android.sh,或传 -DOPENMPT_LIB=...")
endif()
if(NOT EXISTS "${OPENMPT_LIB}")
    message(FATAL_ERROR "libopenmpt.a 不存在: ${OPENMPT_LIB}")
endif()

add_library(orzaudio SHARED
    ${ORZ_SRC}/dispatch/audio_engine.c
    ${ORZ_SRC}/dispatch/orz_dispatch.c
    ${ORZ_SRC}/bp/bp_impl.c
    ${ORZ_SRC}/midi/midi_impl.c
    ${ORZ_SRC}/ym6/ym6_impl.c
    ${ORZ_SRC}/ym6/lhasa_adapter.c
    ${ORZ_SRC}/ym6/lhasa/lh5_decoder.c
    ${ORZ_SRC}/ym6/unlzh_ym.c
    ${ORZ_SRC}/openmpt/openmpt_impl.c
    ${ORZ_SRC}/helpers/cxx_helpers.cpp
    ${ORZ_SRC}/stub_decoders.c
    ${CMAKE_CURRENT_SOURCE_DIR}/orz_jni.c
)

target_include_directories(orzaudio PRIVATE
    ${ORZ_SRC}/include
    ${ORZ_SRC}/dispatch
    ${OPENMPT_INCLUDE}
)

target_compile_definitions(orzaudio PRIVATE
    ORZ_AUDIO_CORE_BUILD=1
    ORZ_HAVE_OPENMPT=1
)

target_compile_options(orzaudio PRIVATE
    -O2
    -fPIC
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
)

set_target_properties(orzaudio PROPERTIES
    C_STANDARD 11
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

target_link_libraries(orzaudio PRIVATE
    "${OPENMPT_LIB}"
    m
    log
    android
    c++_static
)

target_link_options(orzaudio PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
)
