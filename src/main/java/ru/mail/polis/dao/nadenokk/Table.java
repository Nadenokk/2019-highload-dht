package ru.mail.polis.dao.nadenokk;

import java.nio.ByteBuffer;
import java.util.Iterator;
import org.jetbrains.annotations.NotNull;

public interface Table {
    long sizeInBytes();

    @NotNull
    Iterator<Cell> iterator(@NotNull ByteBuffer from);

    void upsert(@NotNull ByteBuffer key, @NotNull ByteBuffer value);

    void remove(@NotNull ByteBuffer key);

    long generation();

}
