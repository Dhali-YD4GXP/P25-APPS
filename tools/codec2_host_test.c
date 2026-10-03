/*
 * Host-side smoke test for the vendored Codec 2 sources.
 * Builds the same core file set used by the Android NDK CMakeLists and
 * round-trips a test tone through each supported bitrate.
 *
 * Build/run via tools/verify_native_host.sh
 */
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include "codec2.h"

int main(void) {
    int modes[] = { CODEC2_MODE_700C, CODEC2_MODE_1600, CODEC2_MODE_3200, CODEC2_MODE_1300 };
    const char *names[] = { "700C", "1600", "3200", "1300" };
    int fail = 0;

    for (int m = 0; m < 4; m++) {
        struct CODEC2 *c = codec2_create(modes[m]);
        if (!c) {
            printf("FAIL: codec2_create(%s)\n", names[m]);
            fail = 1;
            continue;
        }
        int nsam = codec2_samples_per_frame(c);
        int nbytes = codec2_bytes_per_frame(c);
        short *in = malloc(sizeof(short) * nsam);
        short *out = malloc(sizeof(short) * nsam);
        unsigned char *bits = malloc(nbytes);

        for (int i = 0; i < nsam; i++) {
            double t = (double) i / 8000.0;
            in[i] = (short) (12000.0 * sin(2.0 * M_PI * 440.0 * t));
        }
        codec2_encode(c, bits, in);
        codec2_decode(c, out, bits);

        long err = 0;
        for (int i = 0; i < nsam; i++) {
            long d = in[i] - out[i];
            err += d < 0 ? -d : d;
        }
        printf("%-5s nsam=%-4d bytes=%-3d meanAbsErr=%.0f\n",
               names[m], nsam, nbytes, (double) err / nsam);

        free(in); free(out); free(bits);
        codec2_destroy(c);
    }

    printf(fail ? "CODEC2_HOST_TEST_FAIL\n" : "CODEC2_HOST_TEST_PASS\n");
    return fail;
}
