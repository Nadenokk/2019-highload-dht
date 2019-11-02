package ru.mail.polis.service.httprest;

import one.nio.http.Response;
import org.jetbrains.annotations.NotNull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import ru.mail.polis.dao.nadenokk.IteratorsTool;
import ru.mail.polis.dao.nadenokk.Cell;
import ru.mail.polis.dao.nadenokk.Value;

public final class ResponseTools {

    private static final String TIMESTAMP_HEADER = "X-OK-Timestamp";
    
    private ResponseTools(){ }

    /**
     * Get Value from response.
     *
     * @param response response from witch we should be get value.
     */
    @NotNull
    public static Value getDataFromResponse(@NotNull final Response response) throws IOException {

        final String timestamp = response.getHeader(TIMESTAMP_HEADER);

        if(response.getStatus() == 200) {
            if(timestamp == null) {
                throw new IllegalArgumentException("Wrong input data!");
            }
            return Value.present(ByteBuffer.wrap(response.getBody()),
                    Long.parseLong(timestamp));
        } else if(response.getStatus() == 404) {
            if(timestamp == null) {
                return Value.absent();
            } else {
                return Value.removed(Long.parseLong(timestamp));
            }
        } else {
            throw new IOException("IOException while get response from nodes");
        }
    }

    /**
     * Get latest value from storage.
     *
     * @param key by we get data and merge
     * @param cells is iterator of cells
     * @return is value
     */
    @NotNull
    public static Value value(final @NotNull ByteBuffer key,
                              final @NotNull Iterator<Cell> cells) {

        if (!cells.hasNext()) {
            return Value.absent();
        }

        final Cell cell = cells.next();

        if(!cell.getKey().equals(key)) {
            return Value.absent();
        }

        final long timestamp = cell.getValue().getTimeStamp();
        final ByteBuffer value = cell.getValue().getData();

        if (value == null) {
            return Value.removed(timestamp);
        } else {
            return Value.present(value, timestamp);
        }
    }

    /**
     * Create  Response for RestServer.
     *
     * @param value is value from storage
     * @param proxyStatus is status for proxy
     * @return is return response
     */
    @NotNull
    public static Response createResponse(@NotNull final Value value,
                                          final boolean proxyStatus) {

        final Response response;

        switch (value.getState()) {
            case REMOVED: {
                response = new Response(Response.NOT_FOUND, Response.EMPTY);
                if (proxyStatus) {
                    response.addHeader(TIMESTAMP_HEADER + value.getTimeStamp());
                }
                return response;
            }
            case PRESENT: {
                final ByteBuffer val = value.getData();
                final byte[] body = IteratorsTool.toByteArray(val.duplicate());
                response = new Response(Response.OK, body);
                if (proxyStatus) {
                    response.addHeader(TIMESTAMP_HEADER + value.getTimeStamp());
                }
                return response;
            }
            case ABSENT: {
                return new Response(Response.NOT_FOUND, Response.EMPTY);
            }
            default:
                throw new IllegalArgumentException("Wrong input data!");
        }
    }
}
