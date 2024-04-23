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

package org.openjdk.bench.java.lang.stable;

import jdk.internal.lang.StableValue;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Benchmark measuring StableValue performance
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark) // Share the same state instance (for contention)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"--add-exports=java.base/jdk.internal.lang=ALL-UNNAMED", "--enable-preview"})
@Threads(Threads.MAX)   // Benchmark under contention
public class StableBenchmark {

    private static final int VALUE = 42;

    private static final StableValue<Integer> STABLE = init(StableValue.of());
    private static final Supplier<Integer> DCL = new Dcl<>(() -> VALUE);
    private static final List<StableValue<Integer>> LIST = StableValue.ofList(1);
    private static final AtomicReference<Integer> ATOMIC = new AtomicReference<>(VALUE);

    private final StableValue<Integer> stable = init(StableValue.of());
    private final Supplier<Integer> dcl = new Dcl<>(() -> VALUE);
    private final List<StableValue<Integer>> list = StableValue.ofList(1);
    private final AtomicReference<Integer> atomic = new AtomicReference<>(VALUE);

    static {
        LIST.getFirst().setOrThrow(VALUE);
    }

    @Setup
    public void setup() {
        list.getFirst().setOrThrow(VALUE);
    }

    @Benchmark
    public void staticAtomic(Blackhole bh) {
        bh.consume((int)ATOMIC.get());
    }

    @Benchmark
    public void staticStable(Blackhole bh) {
        bh.consume((int)STABLE.orThrow());
    }

    @Benchmark
    public void staticList(Blackhole bh) {
        bh.consume((int)LIST.get(0).orThrow());
    }

    @Benchmark
    public void staticCHI(Blackhole bh) {
        class Holder {
            static final int VALUE = 42;
        }
        bh.consume((int)Holder.VALUE);
    }

    @Benchmark
    public void staticDCL(Blackhole bh) {
        bh.consume((int)DCL.get());
    }

    @Benchmark
    public void instanceAtomic(Blackhole bh) {
        bh.consume((int)atomic.get());
    }

    @Benchmark
    public void instanceDCL(Blackhole bh) {
        bh.consume((int)dcl.get());
    }

    @Benchmark
    public void instanceList(Blackhole bh) {
        bh.consume((int)list.get(0).orThrow());
    }

    @Benchmark
    public void instanceStable(Blackhole bh) {
        bh.consume((int)stable.orThrow());
    }

    private static StableValue<Integer> init(StableValue<Integer> m) {
        m.setOrThrow(VALUE);
        return m;
    }

    // Handles null values
    private static class Dcl<V> implements Supplier<V> {

        private final Supplier<V> supplier;

        private volatile V value;
        private boolean bound;

        public Dcl(Supplier<V> supplier) {
            this.supplier = supplier;
        }

        @Override
        public V get() {
            V v = value;
            if (v == null) {
                if (!bound) {
                    synchronized (this) {
                        v = value;
                        if (v == null) {
                            if (!bound) {
                                value = v = supplier.get();
                                bound = true;
                            }
                        }
                    }
                }
            }
            return v;
        }
    }

}
