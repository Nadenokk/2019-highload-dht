package ru.mail.polis.service.httprest;

import org.jetbrains.annotations.NotNull;
import java.nio.ByteBuffer;
import java.util.Set;

public interface Topology<T> {

    T primaryFor(@NotNull final ByteBuffer key);

    boolean isMe(@NotNull final T node);

    @NotNull
    String[] poolsNodes(@NotNull final int count, @NotNull final ByteBuffer key);

    Set<T> all();
}
