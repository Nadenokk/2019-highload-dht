package ru.mail.polis.dao.nadenokk;

import com.google.common.collect.Iterators;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.SortedMap;
import java.util.Collections;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

public final class MemTable implements Table {
    private final SortedMap<ByteBuffer, Value> map = new ConcurrentSkipListMap<>();
    private final SortedMap<ByteBuffer, Value> unmodifiable = Collections.unmodifiableSortedMap(map);
    private final AtomicLong sizeInBytes = new AtomicLong();
    private final long generation;

    MemTable(final long generation) {
        this.generation = generation;
    }

    public long sizeInBytes() {
        return sizeInBytes.get();
    }

    @NotNull
    @Override
    public Iterator<Cell> iterator(@NotNull final ByteBuffer from) {
        return Iterators.transform(unmodifiable.tailMap(from)
                        .entrySet().iterator(),
                input -> Cell.of(input.getKey(), input.getValue(), generation)
        );
    }

    @Override
    public void upsert(@NotNull final ByteBuffer key, @NotNull final ByteBuffer value) {
        final Value previous = map.put(key, Value.of(value));
        if (previous == null) {
            sizeInBytes.addAndGet(key.remaining() + value.remaining() + Long.BYTES);
        } else if (previous.isRemoved()) {
            sizeInBytes.addAndGet(value.remaining() + Long.BYTES);
        } else {
            sizeInBytes.addAndGet(value.remaining() + Long.BYTES - previous.getData().remaining());
        }
    }

    @Override
    public void remove(@NotNull final ByteBuffer key) {
        final Value previous = map.put(key, Value.tombstone());
        if (previous == null) {
            sizeInBytes.addAndGet(key.remaining());
        } else if (!previous.isRemoved()) {
            sizeInBytes.addAndGet(-previous.getData().remaining());
        }
    }

    @Override
    public long generation() {
        return this.generation;
    }

}
