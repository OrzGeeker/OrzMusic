cmake_minimum_required(VERSION 3.22)
project(orzaudio LANGUAGES C CXX)

# 由 clients/android/audio-kit/scripts/build-android.sh 用 NDK 交叉编译:
#   -DANDROID_ABI=arm64-v8a | armeabi-v7a | x86_64
#   -DORZ_PREBUILT_CORE=<libOrzAudioCore.a>   ← 推荐:用上游 OrzAudioCore 资产
#   -DORZ_PREBUILT_INCLUDE=<native/include>   ← 与 .a 同版本的头文件目录
#   -DOPENMPT_LIB=<libopenmpt.a>              ← 仅 vendored 回退路径需要
# 产物: liborzaudio.so(复制到 audio-kit/src/main/jniLibs/<abi>/)
#
# 两条路径:
#   · ORZ_PREBUILT_CORE:上游已经编好 23 个格式,这里只编 JNI 胶水(秒级)
#   · vendored:本地 audio-kit/native/orzaudio 源码 + libopenmpt(慢,仅离线回退)

set(ORZ_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../orzaudio/Sources/OrzAudioKitCXX")
set(OPENMPT_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libopenmpt/include")

if(DEFINED ORZ_PREBUILT_CORE AND NOT EXISTS "${ORZ_PREBUILT_CORE}")
    message(FATAL_ERROR "ORZ_PREBUILT_CORE 不存在: ${ORZ_PREBUILT_CORE}")
endif()

if(DEFINED ORZ_PREBUILT_CORE)
    # ── 推荐路径:上游 OrzAudioCore 静态库 + 本仓库的 JNI 胶水 ──
    add_library(orzaudio SHARED ${CMAKE_CURRENT_SOURCE_DIR}/orz_jni.c)

    # 头文件优先用资产里的 native/include(与 .a 同版本),否则退回 vendored include
    if(DEFINED ORZ_PREBUILT_INCLUDE AND EXISTS "${ORZ_PREBUILT_INCLUDE}/orz_audio_core.h")
        target_include_directories(orzaudio PRIVATE ${ORZ_PREBUILT_INCLUDE})
    else()
        target_include_directories(orzaudio PRIVATE ${ORZ_SRC}/include)
    endif()
    target_compile_definitions(orzaudio PRIVATE ORZ_AUDIO_CORE_BUILD=1)
    target_compile_options(orzaudio PRIVATE -O2 -fPIC -ffunction-sections -fdata-sections -fvisibility=hidden)

    # 这个 target 只有 .c 源文件,但链接的是 C++ 实现的核心库:
    # 不显式用 CXX 链接器的话,NDK 不会带上 libc++ 运行时
    # (undefined symbol: operator new / __cxa_guard_acquire)。
    set_target_properties(orzaudio PROPERTIES
        C_STANDARD 11
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
        LINKER_LANGUAGE CXX
    )

    target_link_libraries(orzaudio PRIVATE
        "${ORZ_PREBUILT_CORE}"
        z
        m
        log
        android
        c++_static
        c++abi
    )

    target_link_options(orzaudio PRIVATE
        -Wl,--gc-sections
        -Wl,-z,max-page-size=16384
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/orzaudio.map)
    return()
endif()

if(NOT DEFINED OPENMPT_LIB)
    message(FATAL_ERROR "未指定 ORZ_PREBUILT_CORE 或 OPENMPT_LIB —— 见 clients/android/audio-kit/native/README.md")
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
    # 只导出 JNI 入口:链进来的第三方符号一律 local(见 orzaudio.map)
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/orzaudio.map
)
