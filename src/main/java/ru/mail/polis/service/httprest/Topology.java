package ru.mail.polis.service.httprest;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Set;

public interface Topology<T> {
    T primaryFor(@NotNull final ByteBuffer key);

    boolean isMe(@NotNull final T node);

    Set<T> all();
}
