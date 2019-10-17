package ru.mail.polis.dao.nadenokk;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicInteger;
import java.nio.ByteBuffer;

public final class Value implements Comparable<Value> {
    private static final AtomicInteger atomicInteger = new AtomicInteger();
    private final long ts;
    private final ByteBuffer data;

    Value(final long ts, final ByteBuffer data) {
        this.ts = ts;
        this.data = data;
    }

    public static Value of(final ByteBuffer data) {
        return new Value(getTime(), data.duplicate());
    }

    static Value tombstone() {
        return new Value(getTime(), null);
    }

    boolean isRemoved() {
        return data == null;
    }

    ByteBuffer getData() {
        if (data == null) {
            throw new IllegalArgumentException("");
        }
        return data.asReadOnlyBuffer();
    }

    @Override
    public int compareTo(@NotNull final Value o) {
        return Long.compare(o.ts, ts);
    }

    long getTimeStamp() {
        return ts;
    }

    private static long getTime() {
        final long time = System.currentTimeMillis() * 10000 + atomicInteger.incrementAndGet();
        if (atomicInteger.get() > 10000) atomicInteger.set(0);
        return time;
    }
}
