#!/usr/bin/env bash
# Compiles the vendored Codec 2 core (the exact file set used by the Android
# NDK build) with the host gcc and round-trips a test tone. Useful to validate
# the native sources without an Android SDK/NDK.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
C2="$ROOT/app/src/main/cpp/codec2"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

SRCS=(
  dump.c lpc.c nlp.c postfilter.c sine.c codec2.c codec2_fft.c codec2_fifo.c
  kiss_fft.c kiss_fftr.c linreg.c interp.c lsp.c mbest.c newamp1.c phase.c
  quantise.c pack.c filter.c
  codebook.c codebookd.c codebookjmv.c codebookge.c
  codebooknewamp1.c codebooknewamp1_energy.c
  codebooknewamp2.c codebooknewamp2_energy.c
)

FILES=()
for f in "${SRCS[@]}"; do FILES+=("$C2/$f"); done

gcc -O2 -w -I "$C2/.." -I "$C2" -o "$OUT/codec2_host_test" \
    "$ROOT/tools/codec2_host_test.c" "${FILES[@]}" -lm

"$OUT/codec2_host_test"
