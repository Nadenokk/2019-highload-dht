package ru.mail.polis.dao.nadenokk;

import java.nio.ByteBuffer;
import java.util.Collection;
import com.google.common.collect.Iterators;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.Iters;
import java.util.ArrayList;
import java.util.Iterator;

public final class IteratorsTool {
    private IteratorsTool(){

    }

    /**
     * Simple helper to collapse data from tables.
     * @param memTable is table witch collapse their iters with another tables
     * @param fileTables is collection witch collapse theirs iters with table
     * @param from is key from we get data
     * */
    public static Iterator<Cell> data(@NotNull final Table memTable,
                                      @NotNull final Collection<FileTable> fileTables,
                                      @NotNull final ByteBuffer from) {
        final Collection<Iterator<Cell>> filesIterators = new ArrayList<>();
        for (final FileTable fileTable : fileTables) {
            filesIterators.add(fileTable.iterator(from));
        }
        filesIterators.add(memTable.iterator(from));
        final Iterator<Cell> cells = Iters.collapseEquals(Iterators
                .mergeSorted(filesIterators, Cell.COMPARATOR), Cell::getKey);
        return Iterators.filter(cells, cell -> {
            assert cell != null;
            return !cell.getValue().isRemoved();
        });
    }

    /**
    @param buffer ByteBuffer to convert
     **/
    public static byte[] toByteArray(@NotNull final ByteBuffer buffer) {
        final byte[] blk = new byte[buffer.remaining()];
        buffer.get(blk);
        return blk;
    }
}
