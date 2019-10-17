package ru.mail.polis.dao.nadenokk;

import java.util.Iterator;

public class FlushTable {

    private final long generation;
    private final Iterator <Cell> data;
    private final boolean poisonPills;
    private final boolean compactionTable;

    FlushTable(final long generation,
               final Iterator <Cell> data,
               final boolean poisonPills,
               final boolean compactionTable) {
        this.generation = generation;
        this.data = data;
        this.poisonPills = poisonPills;
        this.compactionTable = compactionTable;
    }

    FlushTable(final long generation, final Iterator <Cell> data, final boolean compactionTable) {
        this(generation, data, false, compactionTable);
    }

    public long getGeneration() {
        return generation;
    }

    public Iterator<Cell> data() {
        return data;
    }

    public boolean isPoisonPills() {
        return poisonPills;
    }

    public boolean isCompactionTable() {
        return compactionTable;
    }
}
