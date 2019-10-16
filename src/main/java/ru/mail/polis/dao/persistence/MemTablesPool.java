package ru.mail.polis.dao.persistence;

import java.io.IOException;
import java.io.Closeable;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Iterator;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.NavigableMap;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.collect.Iterators;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.Iters;

public class MemTablesPool implements Table, Closeable {

    private volatile MemTable currentMemTable;
    private final NavigableMap<Long, Table> pendingToFlushTables;
    private long generation;
    private final long flushLimit;
    AtomicLong currentGeneration;
    private final BlockingQueue<FlushTable> flushingQueue;

    private final AtomicBoolean stop = new AtomicBoolean();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

     /**
     * Pool of mem table to flush.
     *
     * @param flushLimit      is the limit above which we flushing mem table
     * @param generation is the start of generation
     **/
    public MemTablesPool(@NotNull final long generation,@NotNull final long flushLimit) {
        this.generation = generation;
        this.flushLimit = flushLimit;
        this.currentMemTable = new MemTable(generation);
        this.pendingToFlushTables = new TreeMap<>();
        this.currentGeneration = new AtomicLong(generation);
        this.flushingQueue = new ArrayBlockingQueue<>(2);
    }

    @Override
    public long sizeInBytes() {
        lock.readLock().lock();
        try {
            return currentMemTable.sizeInBytes();
        }finally {
           lock.readLock().unlock();
        }
    }

    @NotNull
    @Override
    public Iterator<Cell> iterator(final @NotNull ByteBuffer from) throws IOException {
        lock.readLock().lock();
        final Collection<Iterator<Cell>> iterators;
        try {

            iterators = new ArrayList<>(pendingToFlushTables.size() + 1);
            for (final Table table : pendingToFlushTables.descendingMap().values()) {
                iterators.add(table.iterator(from));
            }
            iterators.add(currentMemTable.iterator(from));
        } finally {
            lock.readLock().unlock();
        }
        final Iterator<Cell> mergeIterator = Iterators.mergeSorted(iterators, Cell.COMPARATOR);
        return Iters.collapseEquals(mergeIterator, Cell::getKey);
    }

    @Override
    public void upsert(final @NotNull ByteBuffer key, final @NotNull ByteBuffer value) {
        if(stop.get()) {
            throw new IllegalStateException("Already stopped!");
        }
        try {
            lock.readLock().lock();
            currentMemTable.upsert(key, value);
        } finally {
            lock.readLock().unlock();
        }


        enqueueFlush();
    }

    @Override
    public void remove(final @NotNull ByteBuffer key) {
        if(stop.get()) {
            throw new IllegalStateException("Already stopped!");
        }
        try {
            lock.readLock().lock();
            currentMemTable.remove(key);
        } finally {
            lock.readLock().unlock();
        }
        enqueueFlush();
    }

    private void enqueueFlush() {
            lock.writeLock().lock();
            FlushTable flushTable = null;
             try {

                 if (currentMemTable.sizeInBytes() > flushLimit) {
                     flushTable = new FlushTable(generation,
                            currentMemTable.iterator(LSMDao.nullBuffer),
                            false);
                     pendingToFlushTables.put(generation, currentMemTable);
                     generation = generation + 1;
                     currentMemTable = new MemTable(generation);
                 }
             } finally {
                lock.writeLock().unlock();
            }
            if(flushTable != null) {
                try {
                    flushingQueue.put(flushTable);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
    }

    @Override
    public long generation() {
        lock.readLock().lock();
        try {
            return generation;
        } finally {
            lock.readLock().unlock();
        }
    }

    public FlushTable tableToFlush() throws InterruptedException {
        return flushingQueue.take();
    }

    /**
     * Mark mem table as flushed and remove her from map storage of tables.
     *
     * @param generation is key by which we remove table from storage
     */
    public void flushed(final long generation) {
        lock.writeLock().lock();
        try {
            pendingToFlushTables.remove(generation);
        }
        finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void close() throws IOException {
        if(!stop.compareAndSet(false, true)) {
            return;
        }
        lock.writeLock().lock();
        FlushTable flushTable;
        try {
            flushTable = new FlushTable(generation, currentMemTable.iterator(LSMDao.nullBuffer),
                    true, false);
        } finally {
            lock.writeLock().unlock();
        }

        try {
            flushingQueue.put(flushTable);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Compact values from all tables with current table.
     *
     * @param fileTables is all tables from disk storage
     * @param generation is the start of generation
     * @param base is the path
     */
    public void compact(@NotNull final  Collection<FileTable> fileTables,
                        final long generation,final File base) throws IOException {
        lock.readLock().lock();
        final Iterator<Cell> alive ;
        try {
            alive = IteratorsTool.data(currentMemTable,fileTables,LSMDao.nullBuffer);
        } finally {
            lock.readLock().unlock();
        }
        final File tmp = new File(base, generation + LSMDao.TABLE + LSMDao.TEMP);
        lock.readLock().lock();
        FileTable.writeTable(alive, tmp);
        try {
            for (final FileTable fileTable : fileTables) {
                Files.delete(fileTable.getPath());
            }
            fileTables.clear();
            final File file = new File(base, generation + LSMDao.TABLE + LSMDao.SUFFIX);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE);
            fileTables.add(new FileTable(file, generation));
        }finally {
            lock.readLock().unlock();
        }
    }
}
