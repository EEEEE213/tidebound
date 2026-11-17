package dev.tidebound.world;

/**
 * Small seeded 2D gradient noise (Perlin style), output roughly in [-1, 1].
 * Pure Java so the island layout can be unit tested without Minecraft.
 */
public final class Noise2D {
    private final int[] perm = new int[512];

    public Noise2D(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        long s = seed ^ 0x5DEECE66DL;
        for (int i = 255; i > 0; i--) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            int j = (int) ((s >>> 33) % (i + 1));
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double grad(int h, double x, double y) {
        switch (h & 7) {
            case 0: return x + y;
            case 1: return -x + y;
            case 2: return x - y;
            case 3: return -x - y;
            case 4: return x;
            case 5: return -x;
            case 6: return y;
            default: return -y;
        }
    }

    public double sample(double x, double y) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        double xf = x - xi;
        double yf = y - yi;
        int X = xi & 255;
        int Y = yi & 255;
        double u = fade(xf);
        double v = fade(yf);
        int aa = perm[perm[X] + Y];
        int ab = perm[perm[X] + Y + 1];
        int ba = perm[perm[X + 1] + Y];
        int bb = perm[perm[X + 1] + Y + 1];
        double x1 = lerp(u, grad(aa, xf, yf), grad(ba, xf - 1, yf));
        double x2 = lerp(u, grad(ab, xf, yf - 1), grad(bb, xf - 1, yf - 1));
        return lerp(v, x1, x2) * 0.9;
    }

    /** Fractal sum of {@code octaves} layers starting at the given frequency. */
    public double fbm(double x, double y, double frequency, int octaves) {
        double sum = 0, amp = 1, norm = 0;
        for (int i = 0; i < octaves; i++) {
            sum += amp * sample(x * frequency + i * 31.7, y * frequency - i * 17.3);
            norm += amp;
            amp *= 0.5;
            frequency *= 2;
        }
        return sum / norm;
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }
}
