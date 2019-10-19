package ru.mail.polis.dao.nadenokk;

import com.google.common.collect.Iterators;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.Record;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LSMDao implements DAO {
    private static final Logger LOG = LoggerFactory.getLogger(LSMDao.class);
    public static final String SUFFIX = ".dat";
    public static final String TEMP = ".tmp";
    public static final String TABLE = "ssTable";
    public static final ByteBuffer nullBuffer = ByteBuffer.allocate(0);
    private final File base;
    private final Collection<FileTable> fileTables;
    private final MemTablesPool memTable;
    private final long generation;
    private final Thread flushedThread;

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
        this.fileTables = new ArrayList<>();

        final AtomicLong maxGeneration = new AtomicLong();

        try (Stream<Path> pStream = Files.walk(base.toPath(), 1)
                .filter(p -> p.getFileName().toString().endsWith(SUFFIX))) {
            pStream.collect(Collectors.toList()).forEach(path -> {
                final File file = path.toFile();
                if (!path.getFileName().toString().startsWith("trash")) {
                    final Long tmpGen = Long.parseLong(file.getName().replaceAll(TABLE+SUFFIX,""));
                    try {
                        maxGeneration.set(Math.max(maxGeneration.get(), tmpGen));
                        fileTables.add(new FileTable(file,tmpGen));
                    } catch (IOException e) {
                        LOG.error("I/O error ", e);
                    }
                }
            });
        }
        maxGeneration.incrementAndGet();
        this.generation = maxGeneration.get();
        this.memTable = new MemTablesPool(maxGeneration.get(),flushThreshold);
        this.flushedThread = new Thread(new FlusherTask());
        flushedThread.start();
    }

    @NotNull
    @Override
    public Iterator<Record> iterator(@NotNull final ByteBuffer from) {
        return Iterators.transform(cellIterator(from), cell -> {
            assert cell != null;
            return Record.of(cell.getKey(), cell.getValue().getData());
        });
    }

    @Override
    public void upsert(@NotNull final ByteBuffer key, @NotNull final ByteBuffer value){
        memTable.upsert(key, value);
    }

    private void flush(final long currentGeneration,
                       final boolean isCompactFlush,
                       @NotNull final Iterator<Cell> iterator) throws IOException {
        if(iterator.hasNext()) {
            final File file = new File(base, currentGeneration + TABLE + SUFFIX);
            FileTable.writeTable(iterator, file);
            if (isCompactFlush) {
                fileTables.add(new FileTable(file, currentGeneration));
            }
        }
    }

    @Override
    public void remove(@NotNull final ByteBuffer key) {
        memTable.remove(key);
    }

    @Override
    public void close() {
        memTable.close();
        try {
            flushedThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void compact() throws IOException {
        memTable.compact(fileTables,generation,base);
    }

    @NotNull
    private Iterator<Cell> cellIterator(@NotNull final ByteBuffer from) {
        return IteratorsTool.data(memTable, fileTables, from);
    }

    private final class FlusherTask implements Runnable {

        @Override
        public void run() {
            boolean poisonReceived = false;
            while (!Thread.currentThread().isInterrupted() && !poisonReceived) {
                FlushTable flushTable;
                try {
                    flushTable = memTable.tableToFlush();
                    final Iterator<Cell> data = flushTable.data();
                    final long currentGeneration = flushTable.getGeneration();
                    poisonReceived = flushTable.isPoisonPills();
                    final boolean isCompactTable = flushTable.isCompactionTable();
                    if(isCompactTable || poisonReceived) {
                        flush(currentGeneration,true,data);
                    } else {
                        flush(currentGeneration,false,data);
                    }
                    if(!isCompactTable) {
                        memTable.flushed(currentGeneration);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    LOG.error("I/O error ", e);
                }
            }
        }
    }
}
