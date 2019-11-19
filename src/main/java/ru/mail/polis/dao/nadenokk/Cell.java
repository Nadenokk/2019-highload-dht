package ru.mail.polis.dao.nadenokk;

import java.nio.ByteBuffer;
import java.util.Comparator;

public class Cell {

    public static final Comparator<Cell> COMPARATOR = Comparator.comparing(Cell::getKey)
            .thenComparing(Cell::getValue)
            .thenComparing(Cell::getGeneration);

    private final ByteBuffer key;
    private final Value value;
    private final long generation;

    Cell(final ByteBuffer key, final Value value, final long generation) {
        this.key = key;
        this.value = value;
        this.generation = generation;
    }

    public static Cell of(final ByteBuffer key, final Value value, final long generation) {
        return new Cell(key, value, generation);
    }

    public ByteBuffer getKey() {
        return key.asReadOnlyBuffer();
    }

    public Value getValue() {
        return value;
    }

    public long getGeneration() {
        return generation;
    }
}

