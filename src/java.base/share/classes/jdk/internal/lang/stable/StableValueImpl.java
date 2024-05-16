/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package jdk.internal.lang.stable;

import jdk.internal.lang.StableValue;
import jdk.internal.vm.annotation.DontInline;
import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.Stable;

import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static jdk.internal.lang.stable.StableUtil.*;

public final class StableValueImpl<V> implements StableValue<V> {

    private static final long MUTEX_OFFSET =
            UNSAFE.objectFieldOffset(StableValueImpl.class, "mutex");

    private static final long VALUE_OFFSET =
            UNSAFE.objectFieldOffset(StableValueImpl.class, "value");

    private static final long STATE_OFFSET =
            UNSAFE.objectFieldOffset(StableValueImpl.class, "state");

    /**
     * An internal mutex used rather than synchronizing on `this`. Lazily created.
     * If `null`    , we have not entered a mutex section yet
     * If TOMBSTONE , we do not need synchronization anymore (isSet() && isError() = true)
     * if instanceof Throwable , records a previous error and, we do not need synchronization anymore
     * otherwise    , a distinct synchronization object
     */
    private Object mutex;

    /**
     * If non-null, holds a set value
     * If `null`  , may be unset or hold a set `null` value
     */
    @Stable
    private V value;

    /**
     * If StableUtil.NOT_SET  , a value is not set
     * If StableUtil.NON_NULL , a non-null value is set
     * If StableUtil.NULL     , a `null` value is set
     */
    @Stable
    private int state;

    /**
     * Indicates a computation operation has been invoked. Used to
     * detect circular computation invocations.
     */
    @Stable
    private boolean computeInvoked;

    private StableValueImpl() {}

    @ForceInline
    @Override
    public boolean isSet() {
        int s;
        return (s = state) == NON_NULL || s == NULL ||
                (s = stateVolatile()) == NON_NULL || s == NULL;
    }

    @ForceInline
    @Override
    public boolean isError() {
        return state == ERROR || stateVolatile() == ERROR;
    }

    @ForceInline
    @Override
    public V orThrow() {
        // Optimistically try plain semantics first
        final V v = value;
        if (v != null) {
            // If we happen to see a non-null value under
            // plain semantics, we know a value is set.
            return v;
        }
        if (state == NULL) {
            // If we happen to see a state value of NULL under
            // plain semantics, we know a value is set to `null`.
            return null;
        }
        // Now, fall back to volatile semantics.
        return orThrowVolatile();

/*        // This is intentionally an old switch statement as it generates
        // more compact byte code.
        switch (state) {
            case UNSET:    { throw StableUtil.notSet(); }
            case NULL:     { return null; }
            case NON_NULL: { return value; }
            case ERROR:    { throw StableUtil.error(this); }
            case DUMMY:    { throw shouldNotReachHere(); }
        }
        throw shouldNotReachHere();*/

    }

    @DontInline // Slow-path taken at most once per thread if set
    private V orThrowVolatile() {
        // This is intentionally an old switch statement as it generates
        // more compact byte code.
        switch (stateVolatile()) {
            case UNSET:    { throw StableUtil.notSet(); }
            case NULL:     { return null; }
            case NON_NULL: { return valueVolatile(); }
            case ERROR:    { throw StableUtil.error(this); }
            case DUMMY:    { throw shouldNotReachHere(); }
        }
        throw shouldNotReachHere();
    }

    @ForceInline
    @Override
    public V setIfUnset(V value) {
        if (isSet() || isError()) {
           return orThrow();
        }
        final var m = acquireMutex();
        if (isMutexNotNeeded(m)) {
            return orThrow();
        }
        synchronized (m) {
            if (isSet() || isError()) {
                return orThrow();
            }
            setValue(value);
            return value;
        }
    }

    @ForceInline
    @Override
    public boolean trySet(V value) {
        if (isSet() || isError()) {
            return false;
        }
        final var m = acquireMutex();
        if (isMutexNotNeeded(m)) {
            return false;
        }
        synchronized (m) {
            if (isSet() || isError()) {
                return false;
            }
            setValue(value);
            return true;
        }
    }

    @ForceInline
    @Override
    public V computeIfUnset(Supplier<? extends V> supplier) {
        // Optimistically try plain semantics first
        final V v = value;
        if (v != null) {
            // If we happen to see a non-null value under
            // plain semantics, we know a value is set.
            return v;
        }
        if (state == NULL) {
            // If we happen to see a state value of NULL under
            // plain semantics, we know a value is set to `null`.
            return null;
        }
        // Now, fall back to volatile semantics.
        return computeIfUnsetVolatile(supplier);
    }

    @DontInline // Slow-path taken at most once per thread if set
    private V computeIfUnsetVolatile(Supplier<? extends V> supplier) {
        // This is intentionally an old switch statement as it generates
        // more compact byte code.
        switch (stateVolatile()) {
            case UNSET:    { return computeIfUnsetVolatile0(supplier); }
            case NON_NULL: { return valueVolatile(); }
            case NULL:     { return null; }
            case ERROR:    { throw StableUtil.error(this); }
        }
        throw shouldNotReachHere();
    }

    private V computeIfUnsetVolatile0(Supplier<? extends V> supplier) {
        final var m = acquireMutex();
        if (isMutexNotNeeded(m)) {
            return orThrow();
        }
        synchronized (m) {
            // A value is already set
            if (state != UNSET) {
                return orThrow();
            }
            // A value is not set
            if (computeInvoked) {
                throw stackOverflow(supplier, null);
            }
            computeInvoked = true;
            try {
                V newValue = supplier.get();
                setValue(newValue);
                return newValue;
            } catch (Throwable t) {
                putState(ERROR);
                putMutex(t.getClass());
                throw t;
            }
        }
    }

    @ForceInline
    public V computeIfUnset(int index, IntFunction<? extends V> mapper) {
        return computeIfUnsetShared(mapper, index);
    }

    @ForceInline
    public <K> V computeIfUnset(K key, Function<? super K, ? extends V> mapper) {
        return computeIfUnsetShared(mapper, key);
    }

    @ForceInline
    private <K> V computeIfUnsetShared(Object provider, K key) {
        // Optimistically try plain semantics first
        final V v = value;
        if (v != null) {
            // If we happen to see a non-null value under
            // plain semantics, we know a value is set.
            return v;
        }
        if (state == NULL) {
            return null;
        }
        // Now, fall back to volatile semantics.
        return computeIfUnsetVolatile(provider, key);
    }

    @DontInline
    private <K> V computeIfUnsetVolatile(Object provider, K key) {
        // This is intentionally an old switch statement as it generates
        // more compact byte code.
        switch (stateVolatile()) {
            case UNSET:    { return computeIfUnsetVolatile0(provider, key); }
            case NON_NULL: { return valueVolatile(); }
            case NULL:     { return null; }
            case ERROR:    { throw StableUtil.error(this); }
        }
        throw shouldNotReachHere();
    }

    private <K> V computeIfUnsetVolatile0(Object provider, K key) {
        final var m = acquireMutex();
        if (isMutexNotNeeded(m)) {
            return orThrow();
        }
        synchronized (m) {
            if (state != UNSET) {
                return orThrow();
            }

            // A value is not set
            if (computeInvoked) {
                throw stackOverflow(provider, key);
            }
            computeInvoked = true;
            try {
                @SuppressWarnings("unchecked")
                V newValue = switch (provider) {
                    case Supplier<?> sup     -> (V) sup.get();
                    case IntFunction<?> iFun -> (V) iFun.apply((int) key);
                    case Function<?, ?> func -> ((Function<K, V>) func).apply(key);
                    default                  -> throw shouldNotReachHere();
                };
                setValue(newValue);
                return newValue;
            } catch (Throwable t) {
                putState(ERROR);
                putMutex(t.getClass());
                throw t;
            }
        }
    }

    private static final Function<Object, String> ERROR_MESSAGE_EXTRACTOR = new Function<Object, String>() {
        @Override
        public String apply(Object stableValue) {
            StableValueImpl<?> svi = (StableValueImpl<?>) stableValue;
            return ((Class<?>) svi.acquireMutex())
                    .getName();
        }
    };

    @Override
    public String toString() {
        return StableUtil.toString(this, ERROR_MESSAGE_EXTRACTOR);
    }

    @SuppressWarnings("unchecked")
    private V valueVolatile() {
        return (V)UNSAFE.getReferenceVolatile(this, VALUE_OFFSET);
    }

    private void setValue(V value) {
        if (value != null) {
            putValue(value);
        }
        // Crucially, indicate a value is set _after_ it has actually been set.
        putState(value == null ? NULL : NON_NULL);
        putMutex(TOMBSTONE); // We do not need a mutex anymore
    }

    private void putValue(V value) {
        // This prevents partially initialized objects to be observed
        // under normal memory semantics.
        freeze();
        UNSAFE.putReferenceVolatile(this, VALUE_OFFSET, value);
    }

    private int stateVolatile() {
        return UNSAFE.getIntVolatile(this, STATE_OFFSET);
    }

    private void putState(int newValue) {
        // This prevents `this.value` to be seen before `this.state` is seen
        freeze();
        UNSAFE.putIntVolatile(this, STATE_OFFSET, newValue);
    }

    private Object acquireMutex() {
        Object mutex = UNSAFE.getReferenceVolatile(this, MUTEX_OFFSET);
        if (mutex == null) {
            mutex = caeMutex();
        }
        return mutex;
    }

    private Object caeMutex() {
        final var created = new Object();
        final var witness = UNSAFE.compareAndExchangeReference(this, MUTEX_OFFSET, null, created);
        return witness == null ? created : witness;
    }

    private void putMutex(Object value) {
        UNSAFE.putReferenceVolatile(this, MUTEX_OFFSET, value);
    }

    // Factories

    public static <V> StableValueImpl<V> of() {
        return new StableValueImpl<>();
    }

    public static <V> StableValue<V> ofBackground(ThreadFactory threadFactory,
                                                  Supplier<? extends V> supplier) {
        final StableValue<V> stable = StableValue.of();

        final class BgRunnable implements Runnable {

            volatile Thread thread;

            @Override
            public void run() {
                stable.computeIfUnset(supplier);
                // Exceptions are implicitly captured by the tread's
                // uncaught exception handler.
            }
        }

        final BgRunnable runnable = new BgRunnable();
        Thread bgThread = threadFactory.newThread(runnable);
        runnable.thread = bgThread;
        bgThread.start();
        return stable;
    }

}
