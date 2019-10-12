package ru.mail.polis.dao.persistence;

import com.google.common.collect.Iterators;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.SortedMap;
import java.util.TreeMap;
import com.google.common.base.Function;
import org.checkerframework.checker.nullness.qual.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;



public final class MemTable implements Table {
    private final SortedMap<ByteBuffer, Value> map = new ConcurrentSkipListMap<>();
    private final SortedMap<ByteBuffer, Value> unmodifiable = Collections.unmodifiableSortedMap(map);
    private AtomicLong sizeInBytes = new AtomicLong();
    private final AtomicLong generation = new AtomicLong();

    MemTable(final long generation) {
        this.generation.set(generation);
    }

    public long sizeInBytes() {
        return sizeInBytes.get();
    }

    @NotNull
    @Override
    public Iterator<Cell> iterator(@NotNull final ByteBuffer from) {
        //return Iterators.transform( map.tailMap(from).entrySet().iterator(), e -> new Cell(e.getKey(), e.getValue(), e.));
        final Iterator <Cell> value =  Iterators.transform(unmodifiable.tailMap(from)
                        .entrySet()
                        .iterator(),
                new Function<Map.Entry<ByteBuffer, Value>, Cell>() {
                    @NotNull
                    @Override
                    public Cell apply(Map.@Nullable Entry<ByteBuffer, Value> input) {
                        return Cell.of(input.getKey(), input.getValue(), generation.get());
                    }
                });

        return value;
    }

    @Override
    public void upsert(@NotNull final ByteBuffer key, @NotNull final ByteBuffer value) {
        final Value previous = map.put(key, Value.of(value));
        if (previous == null) {
            sizeInBytes.addAndGet(key.remaining() + value.remaining()+ Long.BYTES);
        } else if (previous.isRemoved()) {
            sizeInBytes.addAndGet( value.remaining() + Long.BYTES);
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
            sizeInBytes.addAndGet( - previous.getData().remaining());
        }
    }

    @Override
    public long generation() {
        return this.generation.get();
    }

}
