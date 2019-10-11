package ru.mail.polis.dao.persistence;

import com.google.common.collect.Iterators;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.io.InputStream;

public final class LSMDao implements DAO {

    private static final Logger logger = LoggerFactory.getLogger(LSMDao.class);
    public static final String SUFFIX = ".dat";
    public static final String TEMP = ".tmp";
    public static final String TABLE = "ssTable";

    public static final ByteBuffer nullBuffer = ByteBuffer.allocate(0);
    private final File base;
    private Collection<FileTable> fileTables;
    private MemTablePools memTable;
    private long generation;
    private final long flushThreshold;
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
        this.flushThreshold = flushThreshold;
        this.fileTables = new ArrayList<>();
        final AtomicLong maxGeneration = new AtomicLong();

        try (Stream<Path> pStream = Files.walk(base.toPath(), 1)
                .filter(p -> p.getFileName().toString().endsWith(SUFFIX))) {
            pStream.collect(Collectors.toList()).forEach(path -> {
                final File file = path.toFile();
                if (!path.getFileName().toString().startsWith("trash")) {
                    final String[] str = file.getName().split(TABLE);
                    try {
                        maxGeneration.set(Math.max(maxGeneration.get(), Long.parseLong(str[0])));
                        fileTables.add(new FileTable(file,Long.parseLong(str[0])));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
        maxGeneration.set(maxGeneration.get() + 1);
        this.generation= maxGeneration.get();
        this.memTable = new MemTablePools(maxGeneration.get(),flushThreshold);
        flushedThread = new Thread(new FlusherTask());
        flushedThread.start();
    }

    @NotNull
    @Override
    public Iterator<Record> iterator(@NotNull final ByteBuffer from) throws IOException {
        return Iterators.transform( cellIterator(from), cell -> Record.of(cell.getKey(), cell.getValue().getData()));
    }

    @Override
    public void upsert(@NotNull final ByteBuffer key, @NotNull final ByteBuffer value) throws IOException {
        memTable.upsert(key, value);
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

    private void flush(final long currentGeneration,final boolean isCompactFlush,@NotNull final Iterator<Cell> iterator) throws IOException {
        if(!iterator.hasNext()) return;
           final File file  =  new File(base, currentGeneration + TABLE + SUFFIX);
           FileTable.writeTable (iterator, file);
           if(isCompactFlush) {
               fileTables.add(new FileTable(file, currentGeneration));
           }
    }

    @Override
    public void remove(@NotNull final ByteBuffer key) throws IOException {
        memTable.remove(key);
    }

    @Override
    public void close() throws IOException {
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
    private Iterator<Cell> cellIterator(@NotNull final ByteBuffer from) throws IOException {
        return IteratorsTool.data(memTable, fileTables, from);
    }

    private void compactDir(final long preGener) throws IOException {
        fileTables = new ArrayList<>();
        Files.walkFileTree(base.toPath(), EnumSet.noneOf(FileVisitOption.class), 1, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path path, final BasicFileAttributes attrs)
                    throws IOException {
                final File file = path.toFile();
                final Pattern FILE_NAME_PATTERN = Pattern.compile(TABLE);
                final Matcher matcher = FILE_NAME_PATTERN.matcher(file.getName());
                if (file.getName().endsWith(SUFFIX) && matcher.find()) {
                    final String[] str = file.getName().split(TABLE);
                    final long currentGeneration = Integer.parseInt(str[0]);
                    if(currentGeneration >= preGener) {
                        fileTables.add(new FileTable(file, currentGeneration));
                        return FileVisitResult.CONTINUE;
                    }
                }
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
        logger.info("Compaction done in time: " + System.currentTimeMillis());
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
                if(isCompactTable) {
                     compactDir(currentGeneration);
                } else {
                    memTable.flushed(currentGeneration);
                }
                } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                } catch (IOException e) {
                      logger.info("Error :" + e.getMessage());
                }
            }
        }
    }
}