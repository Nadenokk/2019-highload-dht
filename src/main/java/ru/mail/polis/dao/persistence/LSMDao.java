package ru.mail.polis.dao.persistence;

import com.google.common.collect.Iterators;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.Iters;
import ru.mail.polis.Record;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.io.InputStream;

public final class LSMDao implements DAO {
    private static final String SUFFIX = ".dat";
    private static final String TEMP = ".tmp";
    private static final String TABLE = "ssTable";

    private final ByteBuffer nullBuffer = ByteBuffer.allocate(0);
    private final long flushThreshold;
    private final File base;
    private final Collection<FileTable> fileTables;
    private Table memTable;
    private int generation;

    /**
     * Creates persistence LSMDao.
     *
     * @param base           folder with FileTable
     * @param flushThreshold threshold memTable's size
     * @throws IOException if an I/O error occurred
     */
    public LSMDao(final File base, final long flushThreshold) throws IOException {
        assert flushThreshold >= 0L;
        this.base = base;
        this.flushThreshold = flushThreshold;
        this.memTable = new MemTable();
        this.fileTables = new ArrayList<>();

        try (Stream<Path> pStream = Files.walk(base.toPath(), 1)
                .filter(p -> p.getFileName().toString().endsWith(SUFFIX))) {
            pStream.collect(Collectors.toList()).forEach(path -> {
                final File file = path.toFile();
                if (!path.getFileName().toString().startsWith("trash")) {
                    final String[] str = file.getName().split(TABLE);
                    try {
                        fileTables.add(new FileTable(file));
                        generation = Math.max(generation, Integer.parseInt(str[0]));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
            this.generation++;
        }
    }

    @NotNull
    @Override
    public Iterator<Record> iterator(@NotNull final ByteBuffer from) throws IOException {
        return Iterators.transform( cellIterator(from), cell -> Record.of(cell.getKey(), cell.getValue().getData()));
    }

    @Override
    public void upsert(@NotNull final ByteBuffer key, @NotNull final ByteBuffer value) throws IOException {
        memTable.upsert(key, value);
        if (memTable.sizeInBytes() >= flushThreshold) {
            flush(memTable.iterator(nullBuffer));
        }
    }

    @Override
    public void upsertStream(@NotNull final ByteBuffer key, @NotNull final InputStream stream) throws IOException {
        int read =0;
        final ByteBuffer value = ByteBuffer.allocateDirect(stream.available());
        value.clear();
        int curent = stream.available();
        final byte[] bytes = new byte[1024];
        while ((read = stream.read(bytes)) != -1) {
            final int shiftBuf = Math.min(curent,bytes.length);
            for (int i =0; i<shiftBuf;i++) {
                value.put(bytes[i]);
            }
            curent =curent - shiftBuf;
        }
        value.flip();
        memTable.upsert(key, value);
        if (memTable.sizeInBytes() >= flushThreshold) {
            flush(memTable.iterator(nullBuffer));
        }
    }

    @Override
    public void getStream(@NotNull final ByteBuffer key,
                          final OutputStream outputStream) throws IOException, NoSuchElementException {
        final Iterator<Record> iter = iterator(key);
        if (!iter.hasNext()) {
            throw new NoSuchElementException("Not found");
        }
        final Record next = iter.next();
        if (next.getKey().equals(key)) {

            final WritableByteChannel channel = Channels.newChannel(outputStream);
            channel.write(next.getValue());
            channel.close();

        } else {
            throw new NoSuchElementException("Not found");
        }
    }

    private void flush(@NotNull final Iterator<Cell> iterator) throws IOException {
        if (!iterator.hasNext()) return;
        final File tmp = new File(base, generation + TABLE + TEMP);
        FileTable.writeTable(iterator, tmp);
        final File dest = new File(base, generation + TABLE + SUFFIX);
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE);
        generation++;
        memTable = new MemTable();
    }

    @Override
    public void remove(@NotNull final ByteBuffer key) throws IOException {
        memTable.remove(key);
        if (memTable.sizeInBytes() >= flushThreshold) {
            flush(memTable.iterator(nullBuffer));
        }
    }

    @Override
    public void close() throws IOException {
        flush(memTable.iterator(nullBuffer));
    }

    @Override
    public void compact() throws IOException {
        final Iterator<Cell> alive = cellIterator(nullBuffer);
        generation = 1;
        final File tmp = new File(base, generation + TABLE + TEMP);
        FileTable.writeTable(alive, tmp);
        for (final FileTable fileTable : fileTables) {
            Files.delete(fileTable.getPath());
        }
        fileTables.clear();
        final File file = new File(base, generation + TABLE +  SUFFIX);
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE);
        fileTables.add(new FileTable(file));
        generation = fileTables.size() + 1;
    }

    @NotNull
    private Iterator<Cell> cellIterator(@NotNull final ByteBuffer from) throws IOException {
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