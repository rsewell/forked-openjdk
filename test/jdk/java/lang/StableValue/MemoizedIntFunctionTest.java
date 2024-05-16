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
 * @summary Basic tests for memoized Supplier
 * @modules java.base/jdk.internal.lang
 * @compile --enable-preview -source ${jdk.version} MemoizedIntFunctionTest.java
 * @compile StableTestUtil.java
 * @run junit/othervm --enable-preview MemoizedIntFunctionTest
 */

import jdk.internal.lang.StableValue;
import org.junit.jupiter.api.Test;

import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MemoizedIntFunctionTest {

    private static final int FIRST = 42;

    @Test
    void memoized() {
        StableTestUtil.CountingIntFunction<Integer> counting = new StableTestUtil.CountingIntFunction<>(i -> i);
        IntFunction<Integer> memoized = StableValue.memoizedIntFunction(FIRST + 1, counting);
        assertEquals(FIRST, memoized.apply(FIRST));
        assertEquals(1, counting.cnt());
        // Make sure the original supplier is not invoked more than once
        assertEquals(FIRST, memoized.apply(FIRST));
        assertEquals(1, counting.cnt());

        assertThrows(IndexOutOfBoundsException.class, () -> memoized.apply(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> memoized.apply(FIRST + 1));
    }

}
