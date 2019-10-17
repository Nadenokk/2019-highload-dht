package ru.mail.polis.dao.nadenokk;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FileTable implements Table {
    private static final Logger LOG = LoggerFactory.getLogger(FileTable.class);
    private final int rows;
    private final IntBuffer offsets;
    private final ByteBuffer cells;
    private final int sizeFileInByte;
    private final Path path;
    private final long currentGeneration;

    FileTable(final File file, final long currentGeneration) throws IOException {
        final int sizeFile = (int) file.length();
        this.path = file.toPath();
        this.sizeFileInByte = sizeFile;
        final ByteBuffer mapped;

        try (FileChannel fc = FileChannel.open(file.toPath(), StandardOpenOption.READ);) {
            assert sizeFile <= Integer.MAX_VALUE;
            mapped = fc.map(FileChannel.MapMode.READ_ONLY, 0L, fc.size())
                    .order(ByteOrder.BIG_ENDIAN);
        }
        // Rows
        rows = mapped.getInt(sizeFile - Integer.BYTES);

        // Offset
        final ByteBuffer offsetBuffer = mapped.duplicate();
        offsetBuffer.position(mapped.limit() - Integer.BYTES * rows - Integer.BYTES);
        offsetBuffer.limit(mapped.limit() - Integer.BYTES);
        this.offsets = offsetBuffer.slice().asIntBuffer();

        // Cells
        final ByteBuffer cellBuffer = mapped.duplicate();
        cellBuffer.limit(offsetBuffer.position());
        this.cells = cellBuffer.slice();
        this.currentGeneration = currentGeneration;
    }

    @Override
    public long sizeInBytes() {
        return sizeFileInByte;
    }

    /**
     * Writes MemTable data to disk.
     *
     * @param cells iterator of MemTable
     * @param file  path of the file where data needs to be written
     * @throws IOException if an I/O error occurred
     */

    static void writeTable(final Iterator<Cell> cells, final File file) throws IOException {
        final List<Integer> offsets = new ArrayList<>();
        try (FileChannel fc = FileChannel.open(file.toPath(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int offset = 0;
            while (cells.hasNext()) {
                final Cell cell = cells.next();
                offsets.add(offset);
                final ByteBuffer key = cell.getKey();
                final int keySize = cell.getKey().remaining();
                final Value value = cell.getValue();
                // Key
                fc.write(fromInt(keySize));
                fc.write(key);
                offset += keySize + Integer.BYTES + Long.BYTES;
                // Timestamp
                if (value.isRemoved()) {
                    fc.write(fromLong(-cell.getValue().getTimeStamp()));
                } else {
                    fc.write(fromLong(cell.getValue().getTimeStamp()));
                }
                // Value
                if (!value.isRemoved()) {
                    final ByteBuffer valueData = value.getData();
                    final int valueSize = value.getData().remaining();
                    fc.write(fromInt(valueSize));
                    fc.write(valueData);
                    offset += Integer.BYTES + valueSize;
                }
            }
            // Offsets
            for (final Integer anOffset : offsets) {
                fc.write(fromInt(anOffset));
            }
            // Cells
            fc.write(fromInt(offsets.size()));
        } catch (IOException e) {
            LOG.error("I/O error ", e);
        }
    }

    private ByteBuffer keyAt(final int i) {
        assert 0 <= i && i < rows;
        final int offset = offsets.get(i);
        final int keySize = cells.getInt(offset);
        final ByteBuffer key = cells.duplicate();
        key.position(offset + Integer.BYTES);
        key.limit(key.position() + keySize);
        return key.slice();
    }

    private Cell cellAt(final int i) {
        assert 0 <= i && i < rows;
        int offset = offsets.get(i);
        // Key
        final int keySize = cells.getInt(offset);
        offset += Integer.BYTES;
        final ByteBuffer key = cells.duplicate();
        key.position(offset);
        key.limit(key.position() + keySize);
        offset += keySize;
        // Timestamp
        final long timestamp = cells.getLong(offset);
        offset += Long.BYTES;
        if (timestamp < 0) {
            return new Cell(key.slice(), new Value(-timestamp, null), currentGeneration);
        } else {
            final int valueSize = cells.getInt(offset);
            offset += Integer.BYTES;
            final ByteBuffer value = cells.duplicate();
            value.position(offset);
            value.limit(value.position() + valueSize)
                    .position(offset)
                    .limit(offset + valueSize);
            return new Cell(key.slice(), new Value(timestamp, value.slice()), currentGeneration);
        }
    }

    private int position(final ByteBuffer from) {
        int left = 0;
        int right = rows - 1;
        while (left <= right) {
            final int mid = left + (right - left) / 2;
            final int cmp = keyAt(mid).compareTo(from);
            if (cmp < 0) {
                left = mid + 1;
            } else if (cmp > 0) {
                right = mid - 1;
            } else {
                return mid;
            }
        }
        return left;
    }

    @NotNull
    @Override
    public Iterator<Cell> iterator(@NotNull final ByteBuffer from) {
        return new Iterator<>() {
            int next = position(from);

            @Override
            public boolean hasNext() {
                return next < rows;
            }

            @Override
            public Cell next() {
                assert hasNext();
                return cellAt(next++);
            }
        };
    }

    static ByteBuffer fromInt(final int value) {
        final ByteBuffer result = ByteBuffer.allocate(Integer.BYTES);
        result.putInt(value);
        result.rewind();
        return result;
    }

    static ByteBuffer fromLong(final long value) {
        final ByteBuffer result = ByteBuffer.allocate(Long.BYTES);
        result.putLong(value);
        result.rewind();
        return result;
    }

    @Override
    public void upsert(@NotNull final ByteBuffer key, @NotNull final ByteBuffer value) {
        throw new UnsupportedOperationException("");
    }

    @Override
    public void remove(@NotNull final ByteBuffer key) {
        throw new UnsupportedOperationException("");
    }

    public Path getPath() {
        return path;
    }

    @Override
    public long generation() {
        return currentGeneration;
    }
}
