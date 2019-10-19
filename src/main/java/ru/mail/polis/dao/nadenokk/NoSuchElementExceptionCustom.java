package ru.mail.polis.dao.nadenokk;

import org.jetbrains.annotations.NotNull;
import java.util.NoSuchElementException;

@SuppressWarnings("serial")
public class NoSuchElementExceptionCustom extends NoSuchElementException {

    /** Custom lite extends NoSuchElement.
     *
     * @param mess message with problem cause
     */
    public NoSuchElementExceptionCustom(@NotNull final String mess){
        super(mess);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        synchronized (this) {
            return this;
        }
    }
}