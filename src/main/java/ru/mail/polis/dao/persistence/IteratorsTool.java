package ru.mail.polis.dao.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;

import com.google.common.collect.Iterators;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.Iters;
import java.util.ArrayList;
import java.util.Iterator;

public class IteratorsTool {

    @NotNull
    public static final Iterator<Cell> data(@NotNull final Table memTable,
                                      @NotNull final Collection<FileTable> fileTables,
                                      @NotNull final ByteBuffer from) throws IOException {
        final Collection<Iterator<Cell>> filesIterators = new ArrayList<>();
        for (final FileTable fileTable : fileTables) {
            filesIterators.add(fileTable.iterator(from));
        }
        filesIterators.add(memTable.iterator(from));
        final Iterator<Cell> cells = Iters.collapseEquals(Iterators
                .mergeSorted(filesIterators, Cell.COMPARATOR), Cell::getKey);
        return Iterators.filter(cells, cell -> !cell.getValue().isRemoved());
    }
}
