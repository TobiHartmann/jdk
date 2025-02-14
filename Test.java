/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.util.Random;

public class Test {
    static final int LEN = 1024;
    static final Random RAND = new Random();

    static final int[] INT_VALUES = new int[LEN];
    static final int[] INT_EXPECTED = new int[LEN];
    static final int[] INT_RESULT = new int[LEN];

    static int intCounter = Integer.MIN_VALUE;

    public static boolean testInt() {
        boolean done = false;
        
        // Non-vectorized loop as baseline (not vectorized because source array is initialized)
        for (int i = 0; i < LEN; ++i) {
            INT_VALUES[i] = intCounter++;
            if (intCounter == Integer.MAX_VALUE) {
                done = true;
            }
            INT_EXPECTED[i] = Integer.numberOfLeadingZeros(INT_VALUES[i]);
        }
        // Vectorized loop
        for (int i = 0; i < LEN; ++i) {
            INT_RESULT[i] = Integer.numberOfLeadingZeros(INT_VALUES[i]);
        }
        // Compare results
        for (int i = 0; i < LEN; ++i) {
            if (INT_RESULT[i] != INT_EXPECTED[i]) {
                throw new RuntimeException("Unexpected result for " + INT_VALUES[i] + ": " + INT_RESULT[i] + ", expected " + INT_EXPECTED[i]);
            }
        }
        return done;
    }

    static final short[] SHORT_VALUES = new short[LEN];
    static final short[] SHORT_EXPECTED = new short[LEN];
    static final short[] SHORT_RESULT = new short[LEN];

    static short shortCounter = Short.MIN_VALUE;

    public static boolean testShort() {
        boolean done = false;
        
        // Non-vectorized loop as baseline (not vectorized because source array is initialized)
        for (int i = 0; i < LEN; ++i) {
            SHORT_VALUES[i] = shortCounter++;
            if (shortCounter == Short.MAX_VALUE) {
                done = true;
            }
            SHORT_EXPECTED[i] = (short)Integer.numberOfLeadingZeros(SHORT_VALUES[i]);
        }
        // Vectorized loop
        for (int i = 0; i < LEN; ++i) {
            SHORT_RESULT[i] = (short)Integer.numberOfLeadingZeros(SHORT_VALUES[i]);
        }
        // Compare results
        for (int i = 0; i < LEN; ++i) {
            if (SHORT_RESULT[i] != SHORT_EXPECTED[i]) {
                throw new RuntimeException("Unexpected result for " + SHORT_VALUES[i] + ": " + SHORT_RESULT[i] + ", expected " + SHORT_EXPECTED[i]);
            }
        }
        return done;
    }

    static final byte[] BYTE_VALUES = new byte[LEN];
    static final byte[] BYTE_EXPECTED = new byte[LEN];
    static final byte[] BYTE_RESULT = new byte[LEN];

    static byte byteCounter = Byte.MIN_VALUE;

    public static boolean testByte() {
        boolean done = false;
        
        // Non-vectorized loop as baseline (not vectorized because source array is initialized)
        for (int i = 0; i < LEN; ++i) {
            BYTE_VALUES[i] = byteCounter++;
            if (byteCounter == Byte.MAX_VALUE) {
                done = true;
            }
            BYTE_EXPECTED[i] = (byte)Integer.numberOfLeadingZeros(BYTE_VALUES[i]);
        }
        // Vectorized loop
        for (int i = 0; i < LEN; ++i) {
            BYTE_RESULT[i] = (byte)Integer.numberOfLeadingZeros(BYTE_VALUES[i]);
        }
        // Compare results
        for (int i = 0; i < LEN; ++i) {
            if (BYTE_RESULT[i] != BYTE_EXPECTED[i]) {
                System.out.println("Unexpected result for " + BYTE_VALUES[i] + ": " + BYTE_RESULT[i] + ", expected " + BYTE_EXPECTED[i]);
            }
        }
        return done;
    }

    static final long[] LONG_VALUES = new long[LEN];
    static final long[] LONG_EXPECTED = new long[LEN];
    static final long[] LONG_RESULT = new long[LEN];

    static int longIterations = 100_000_000;

    public static boolean testLong() {
        boolean done = false;
        
        // Non-vectorized loop as baseline (not vectorized because source array is initialized)
        for (int i = 0; i < LEN; ++i) {
            // Use random values because the long range is too large to iterate over it
            LONG_VALUES[i] = RAND.nextLong();
            if (longIterations-- == 0) {
                done = true;
            }
            LONG_EXPECTED[i] = Long.numberOfLeadingZeros(LONG_VALUES[i]);
        }
        // Vectorized loop
        for (int i = 0; i < LEN; ++i) {
            LONG_RESULT[i] = Long.numberOfLeadingZeros(LONG_VALUES[i]);
        }
        // Compare results
        for (int i = 0; i < LEN; ++i) {
            if (LONG_RESULT[i] != LONG_EXPECTED[i]) {
                throw new RuntimeException("Unexpected result for " + LONG_VALUES[i] + ": " + LONG_RESULT[i] + ", expected " + LONG_EXPECTED[i]);
            }
        }
        return done;
    }

    public static void main(String[] args) {
        // Run twice to make sure compiled code is used from the beginning
        for (int i = 0; i < 2; ++i) {
            while (!testLong()) ;
            while (!testInt()) ;
            while (!testShort()) ;
        }
        // For byte we need a few more iterations to trigger compilation
        for (int i = 0; i < 10; ++i) {
            while (!testByte()) ;
        }
    }
}
