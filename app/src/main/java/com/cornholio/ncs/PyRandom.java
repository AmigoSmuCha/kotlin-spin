package com.cornholio.ncs;

/**
 * The Mersenne Twister as Python's random.Random and numpy's legacy
 * RandomState use it, so the port draws exactly the numbers cornholio draws:
 * the demo song's hats, Kerosene's pulses, the dust, Diffision's grain.
 */
public final class PyRandom {
    private final int[] mt = new int[624];
    private int mti;

    private PyRandom() {}

    /** random.Random(seed) for an int seed (init_by_array of its 32-bit words). */
    public static PyRandom python(long seed) {
        PyRandom r = new PyRandom();
        long a = Math.abs(seed);
        int words = 0;
        int[] key = new int[3];
        do {
            key[words++] = (int) (a & 0xFFFFFFFFL);
            a >>>= 32;
        } while (a != 0);
        int[] k = new int[words];
        System.arraycopy(key, 0, k, 0, words);
        r.initByArray(k);
        return r;
    }

    /** numpy.random.RandomState(seed) for a 32-bit int seed (init_genrand). */
    public static PyRandom numpy(long seed) {
        PyRandom r = new PyRandom();
        r.initGenrand((int) seed);
        return r;
    }

    private void initGenrand(int s) {
        mt[0] = s;
        for (mti = 1; mti < 624; mti++) {
            int p = mt[mti - 1];
            mt[mti] = 1812433253 * (p ^ (p >>> 30)) + mti;
        }
    }

    private void initByArray(int[] key) {
        initGenrand(19650218);
        int i = 1, j = 0;
        int n = 624;
        for (int k = Math.max(n, key.length); k > 0; k--) {
            int p = mt[i - 1];
            mt[i] = (mt[i] ^ ((p ^ (p >>> 30)) * 1664525)) + key[j] + j;
            i++;
            j++;
            if (i >= n) {
                mt[0] = mt[n - 1];
                i = 1;
            }
            if (j >= key.length) j = 0;
        }
        for (int k = n - 1; k > 0; k--) {
            int p = mt[i - 1];
            mt[i] = (mt[i] ^ ((p ^ (p >>> 30)) * 1566083941)) - i;
            i++;
            if (i >= n) {
                mt[0] = mt[n - 1];
                i = 1;
            }
        }
        mt[0] = 0x80000000;
        mti = 624;
    }

    /** genrand_uint32 */
    public int next32() {
        if (mti >= 624) {
            int kk;
            for (kk = 0; kk < 624 - 397; kk++) {
                int y = (mt[kk] & 0x80000000) | (mt[kk + 1] & 0x7fffffff);
                mt[kk] = mt[kk + 397] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            }
            for (; kk < 623; kk++) {
                int y = (mt[kk] & 0x80000000) | (mt[kk + 1] & 0x7fffffff);
                mt[kk] = mt[kk + (397 - 624)] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            }
            int y = (mt[623] & 0x80000000) | (mt[0] & 0x7fffffff);
            mt[623] = mt[396] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            mti = 0;
        }
        int y = mt[mti++];
        y ^= (y >>> 11);
        y ^= (y << 7) & 0x9d2c5680;
        y ^= (y << 15) & 0xefc60000;
        y ^= (y >>> 18);
        return y;
    }

    /** random.random(): 53 bits, in [0, 1). */
    public double random() {
        long a = (next32() & 0xFFFFFFFFL) >>> 5, b = (next32() & 0xFFFFFFFFL) >>> 6;
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0);
    }

    public double uniform(double a, double b) {
        return a + (b - a) * random();
    }

    private long getrandbits(int k) {
        if (k == 0) return 0;
        return (next32() & 0xFFFFFFFFL) >>> (32 - k);
    }

    /** random.randrange(n) for 0 < n < 2^32. */
    public long randbelow(long n) {
        int k = 64 - Long.numberOfLeadingZeros(n);
        long r = getrandbits(k);
        while (r >= n) r = getrandbits(k);
        return r;
    }

    /** random.randint(a, b), both ends included. */
    public int randint(int a, int b) {
        return a + (int) randbelow((long) b - a + 1);
    }

    /** numpy RandomState.randint(low, high): masked rejection on 32-bit draws. */
    public int npRandint(int low, int high) {
        long rng = (long) high - low - 1;
        if (rng == 0) return low;
        long mask = rng;
        mask |= mask >>> 1; mask |= mask >>> 2; mask |= mask >>> 4; mask |= mask >>> 8; mask |= mask >>> 16;
        long v;
        do {
            v = (next32() & 0xFFFFFFFFL) & mask;
        } while (v > rng);
        return (int) (low + v);
    }
}
