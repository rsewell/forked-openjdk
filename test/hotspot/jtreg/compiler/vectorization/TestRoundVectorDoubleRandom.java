/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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

/**
 * @test
 * @key randomness
 * @bug 8321011
 * @summary Test vector intrinsic for Math.round(double) in full 64 bits range.
 *
 * @requires vm.compiler2.enabled
 *
 * @library /test/lib /
 * @run main compiler.vectorization.TestRoundVectorDoubleRandom
 */

package compiler.vectorization;

import java.util.Random;
import compiler.lib.ir_framework.DontCompile;
import compiler.lib.ir_framework.IR;
import compiler.lib.ir_framework.IRNode;
import compiler.lib.ir_framework.Run;
import compiler.lib.ir_framework.RunInfo;
import compiler.lib.ir_framework.Test;
import compiler.lib.ir_framework.TestFramework;
import compiler.lib.ir_framework.Warmup;

public class TestRoundVectorDoubleRandom {
  private static final int ITERS  = 11000;
  private static final int ARRLEN = 997;
  private static final double ADD_INIT = -7500.;

  private static final double[] input = new double[ARRLEN];
  private static final long [] res = new long[ARRLEN];
  private static final Random rand = new Random();

  public static void main(String args[]) {
    TestFramework.runWithFlags("-XX:-TieredCompilation", "-XX:CompileThresholdScaling=0.3");
    TestFramework.runWithFlags("-XX:-TieredCompilation", "-XX:CompileThresholdScaling=0.3", "-XX:MaxVectorSize=8");
    TestFramework.runWithFlags("-XX:-TieredCompilation", "-XX:CompileThresholdScaling=0.3", "-XX:MaxVectorSize=16");
    TestFramework.runWithFlags("-XX:-TieredCompilation", "-XX:CompileThresholdScaling=0.3", "-XX:MaxVectorSize=32");
  }

  @DontCompile
  long golden_round(double d) {
    return Math.round(d);
  }

  @Test
  @IR(counts = {IRNode.ROUND_VD, "> 0"},
      applyIfPlatform = {"x64", "true"},
      applyIfCPUFeature = {"avx512dq", "true"})
  @IR(counts = {IRNode.ROUND_VD, "> 0"},
      applyIfPlatform = {"aarch64", "true"})
  void test_round(long[] a0, double[] a1) {
    for (int i = 0; i < a0.length; i+=1) {
      a0[i] = Math.round(a1[i]);
    }
  }

  @Run(test = "test_round")
  @Warmup(ITERS)
  void test_rounds(RunInfo runInfo) {
    // Initialize
    for (int i = 0; i < ARRLEN; i++) {
      double val = ADD_INIT+(double)i;
      input[i] = val;
    }

    test_round(res, input);
    // skip test/verify when warming up
    if (runInfo.isWarmUp()) {
      return;
    }

    int errn = 0;
    final int eShift = 52;
    final int eWidth = 11;
    final int eBound = 1 << eWidth;
    final int fWidth = eShift;
    final long fBound = 1 << fWidth;
    final int fNum = 256;

    // prepare test data
    long fis[] = new long[fNum];
    int fidx = 0;
    for (; fidx < fWidth; fidx++) {
      fis[fidx] = 1 << fidx;
    }
    fis[fidx++] = 0;
    for (; fidx < fNum; fidx++) {
      fis[fidx] = rand.nextLong(fBound);
    }

    // run test & verify
    for (long fi : fis) {
      final int eStart = rand.nextInt(9);
      final int eStep = (1 << 3) + rand.nextInt(3);
      for (int ei = eStart; ei < eBound; ei += eStep) {
        int eiIdx = ei/eStep;
        long bits = (ei << eShift) + fi;
        input[eiIdx*2] = Double.longBitsToDouble(bits);
        bits = bits | (1 << 63);
        input[eiIdx*2+1] = Double.longBitsToDouble(bits);
      }

      // test
      test_round(res, input);

      // verify results
      for (int ei = eStart; ei < eBound; ei += eStep) {
        int eiIdx = ei/eStep;
        for (int sign = 0; sign < 2; sign++) {
          int idx = eiIdx * 2 + sign;
          if (res[idx] != Math.round(input[idx])) {
            errn++;
            System.err.println("round error, input: " + input[idx] +
                               ", res: " + res[idx] + "expected: " + Math.round(input[idx]) +
                               ", input hex: " + Double.doubleToLongBits(input[idx]) +
                               ", fi: " + fi + ", ei: " + ei + ", sign: " + sign);
          }
        }
      }
    }
    if (errn > 0) {
      throw new RuntimeException("There are some round error detected!");
    }
  }
}
