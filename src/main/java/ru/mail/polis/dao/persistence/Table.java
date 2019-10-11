package ru.mail.polis.dao.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;

import org.jetbrains.annotations.NotNull;

public interface Table {
    long sizeInBytes();

    @NotNull
    Iterator<Cell> iterator(@NotNull ByteBuffer from) throws IOException;

    void upsert(@NotNull ByteBuffer key, @NotNull ByteBuffer value) throws IOException;

    void remove(@NotNull ByteBuffer key) throws IOException;

    long generation();

}