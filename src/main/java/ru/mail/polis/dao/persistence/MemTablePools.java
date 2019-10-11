package ru.mail.polis.dao.persistence;

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

import com.google.common.collect.Iterators;
import org.jetbrains.annotations.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.dao.Iters;

import java.util.concurrent.atomic.AtomicLong;

import java.io.IOException;
import java.util.NavigableMap;
import java.util.ArrayList;

public class MemTablePools implements Table, Closeable {

     private static final Logger logger = LoggerFactory.getLogger(MemTablePools.class);

    private volatile MemTable currentMemTable;
    private final NavigableMap<Long, Table> pendingToFlushTables;
    private long generation;
    private final long flushLimit;
    private final AtomicLong currentGeneration;
    private final BlockingQueue<FlushTable> flushingQueue;

    private final AtomicBoolean stop = new AtomicBoolean();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public MemTablePools(final long generation, final long flushLimit) {
        this.generation = generation;
        this.flushLimit = flushLimit;
        this.currentMemTable = new MemTable(generation);
        this.pendingToFlushTables = new TreeMap<>();
        this.currentGeneration = new AtomicLong(generation);
        this.flushingQueue = new ArrayBlockingQueue<>(2);
    }

    public long getGeneration() {
        lock.readLock().lock();
        try {
            return generation;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public long sizeInBytes() {
        lock.readLock().lock();
        try {
            long size = currentMemTable.sizeInBytes();
            return size;
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
        Iterator<Cell> mergeIterator = Iterators.mergeSorted(iterators, Cell.COMPARATOR);
        final Iterator <Cell> withoutEquals = Iters.collapseEquals(mergeIterator, Cell::getKey);
        return withoutEquals;
    }


    @Override
    public void upsert(final @NotNull ByteBuffer key, final @NotNull ByteBuffer value) {
        if(stop.get()) {
            throw new IllegalStateException("Already stopped!");
        }
        currentMemTable.upsert(key, value);
        enqueueFlush();
    }

    @Override
    public void remove(final @NotNull ByteBuffer key) {
        if(stop.get()) {
            throw new IllegalStateException("Already stopped!");
        }
        currentMemTable.remove(key);
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
            flushTable = new FlushTable(generation, currentMemTable.iterator(LSMDao.nullBuffer), true, false);
        } finally {
            lock.writeLock().unlock();
        }

        try {
            flushingQueue.put(flushTable);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
public void compact(@NotNull final  Collection<FileTable> fileTables,long generation,File base) throws IOException {
        lock.readLock().lock();
        final Iterator<Cell> alive ;
        try {
            alive = IteratorsTool.data(currentMemTable,fileTables,LSMDao.nullBuffer);
        } finally {
            lock.readLock().unlock();
        }
        final File tmp = new File(base, generation + LSMDao.TABLE + LSMDao.TEMP);
        FileTable.writeTable(alive, tmp);
        for (final FileTable fileTable : fileTables) {
            Files.delete(fileTable.getPath());
        }
        fileTables.clear();
        final File file = new File(base, generation + LSMDao.TABLE +  LSMDao.SUFFIX);
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE);
        fileTables.add(new FileTable(file,generation));
    }
}
