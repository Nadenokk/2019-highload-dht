package ru.mail.polis.dao.nadenokk;

import org.jetbrains.annotations.NotNull;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.ByteBuffer;

public final class Value implements Comparable<Value> {

    private static final Value ABSENT = new Value(-1,null, State.ABSENT);
    private static final AtomicInteger atomicInteger = new AtomicInteger();
    private final long ts;
    private final ByteBuffer data;
    private final State state;

    public enum State {
        ABSENT,
        PRESENT,
        REMOVED
    }

    Value(final long ts, final ByteBuffer data,final State state) {
        this.ts = ts;
        this.data = data;
        this.state = state;
    }

    public static Value of(final ByteBuffer data) {
        return new Value(getTime(), data.duplicate(),State.PRESENT);
    }

    static Value tombstone() {
        return new Value(getTime(), null,State.REMOVED);
    }

    boolean isRemoved() {
        return data == null;
    }

    public ByteBuffer getData() {
        return data;
    }

    @Override
    public int compareTo(@NotNull final Value o) { return Long.compare(o.ts, ts);
    }

    public long getTimeStamp() {
        return ts;
    }

    public State getState() {
        return state;
    }

    public static Value absent(){
        return ABSENT;
    }

    /**
     * Present (alive) value witch we want to read by timestamp.
     * @param data us data in this value.
     * @param ts is timestamp in this value.
     */
    public static Value present(@NotNull final ByteBuffer data,
                                final long ts) {
        return new Value(
                ts,
                data,
                State.PRESENT
        );
    }

    /**
     * Removed (dead) value in storage.
     * @param ts is timestamp of this value.
     */
    public static Value removed(final long ts) {
        return new Value(
                ts,
                null,
                State.REMOVED
        );
    }

    private static long getTime() {
        final long time = System.currentTimeMillis() * 10000 + atomicInteger.incrementAndGet();
        if (atomicInteger.get() > 10000) atomicInteger.set(0);
        return time;
    }
}
