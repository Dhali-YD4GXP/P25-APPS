cmake_minimum_required(VERSION 3.22.1)
project(p25codec2 C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)

set(CODEC2_DIR ${CMAKE_CURRENT_SOURCE_DIR}/codec2)

# Core Codec 2 speech vocoder sources only (no FreeDV/OFDM modem, no LDPC).
# Codebooks are pre-generated (codebook*.c) so no host tool is required.
set(CODEC2_SOURCES
    ${CODEC2_DIR}/dump.c
    ${CODEC2_DIR}/lpc.c
    ${CODEC2_DIR}/nlp.c
    ${CODEC2_DIR}/postfilter.c
    ${CODEC2_DIR}/sine.c
    ${CODEC2_DIR}/codec2.c
    ${CODEC2_DIR}/codec2_fft.c
    ${CODEC2_DIR}/codec2_fifo.c
    ${CODEC2_DIR}/kiss_fft.c
    ${CODEC2_DIR}/kiss_fftr.c
    ${CODEC2_DIR}/linreg.c
    ${CODEC2_DIR}/interp.c
    ${CODEC2_DIR}/lsp.c
    ${CODEC2_DIR}/mbest.c
    ${CODEC2_DIR}/newamp1.c
    ${CODEC2_DIR}/phase.c
    ${CODEC2_DIR}/quantise.c
    ${CODEC2_DIR}/pack.c
    ${CODEC2_DIR}/filter.c
    ${CODEC2_DIR}/codebook.c
    ${CODEC2_DIR}/codebookd.c
    ${CODEC2_DIR}/codebookjmv.c
    ${CODEC2_DIR}/codebookge.c
    ${CODEC2_DIR}/codebooknewamp1.c
    ${CODEC2_DIR}/codebooknewamp1_energy.c
    ${CODEC2_DIR}/codebooknewamp2.c
    ${CODEC2_DIR}/codebooknewamp2_energy.c
)

add_library(p25codec2 SHARED
    codec2_jni.cpp
    ${CODEC2_SOURCES}
)

# codec2.h does `#include <codec2/version.h>`; local quoted includes resolve
# relative to their own file, so both directories are on the search path.
target_include_directories(p25codec2 PRIVATE
    ${CODEC2_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_options(p25codec2 PRIVATE -Wno-unused-parameter -Wno-sign-compare)
target_link_libraries(p25codec2 log m)
