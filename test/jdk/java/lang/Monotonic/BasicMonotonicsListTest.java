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

/* @test
 * @summary Basic tests for the Monotonics utility class
 * @compile --enable-preview -source ${jdk.version} BasicMonotonicsListTest.java
 * @run junit/othervm --enable-preview BasicMonotonicsListTest
 */

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

final class BasicMonotonicsListTest {

    private static final int SIZE = 7;
    private static final int INDEX = 2;

    private List<Monotonic<Integer>> list;

    @BeforeEach
    void setup() {
        list = Monotonic.ofList(SIZE);
    }

    @Test
    void listComputeIfAbsent() {
        Integer v = Monotonics.computeIfAbsent(list, INDEX, i -> i);
        assertEquals(INDEX, v);
        for (int i = 0; i < SIZE; i++) {
            Monotonic<Integer> m = list.get(i);
            if (i == INDEX) {
                assertTrue(m.isPresent());
                assertEquals(INDEX, m.get());
            } else {
                assertFalse(m.isPresent());
            }
        }
    }

    @Test
    void listComputeIfAbsentNull() {
        Integer v = Monotonics.computeIfAbsent(list, INDEX, i -> null);
        assertNull(v);
        for (int i = 0; i < SIZE; i++) {
            Monotonic<Integer> m = list.get(i);
            if (i == INDEX) {
                assertTrue(m.isPresent());
                assertNull(m.get());
            } else {
                assertFalse(m.isPresent());
            }
        }
    }

    @Test
    void listComputeIfAbsentThrows() {
        assertThrows(UnsupportedOperationException.class, () ->
                Monotonics.computeIfAbsent(list, INDEX, i -> {
                    throw new UnsupportedOperationException();
                })
        );
        for (int i = 0; i < SIZE; i++) {
            Monotonic<Integer> m = list.get(i);
            assertFalse(m.isPresent());
        }
    }

    @ParameterizedTest
    @MethodSource("nullOperations")
    void npe(String name, Consumer<List<Monotonic<Integer>>> op) {
        assertThrows(NullPointerException.class, () -> op.accept(list), name);
    }

    private static Stream<Arguments> nullOperations() {
        return Stream.of(
                Arguments.of("computeIfAbsent(L, i, null)",  asListConsumer(l -> Monotonics.computeIfAbsent(l, 0, null))),
                Arguments.of("computeIfAbsent(null, i, M)",  asListConsumer(l -> Monotonics.computeIfAbsent(null, 0, i -> i))),
                Arguments.of("asMemoized(i, null, b)",       asListConsumer(l -> Monotonics.asMemoized(SIZE, null, false)))
        );
    }

    private static Consumer<List<Monotonic<Integer>>> asListConsumer(Consumer<List<Monotonic<Integer>>> consumer) {
        return consumer;
    }


}
