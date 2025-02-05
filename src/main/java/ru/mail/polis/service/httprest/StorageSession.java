package ru.mail.polis.service.httprest;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Response;
import one.nio.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.Iterator;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.mail.polis.Record;
import ru.mail.polis.dao.nadenokk.IteratorsTool;

public class StorageSession extends HttpSession{

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DELIMITER = "\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY_CHUNK = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    private static final Logger log = LoggerFactory.getLogger(StorageSession.class);

    private Iterator<Record> data;

    StorageSession(
            @NotNull final Socket socket,
            @NotNull final HttpServer server) {
        super(socket, server);
    }

    /**
     * Range streaming data as Iterator to socket.
     * @param records is iterator as data for stream.
     * @throws IOException throw exception.
     */
    void stream(@NotNull final Iterator<Record> records) throws IOException {
        this.data = records;
        final Response response = new Response(Response.OK);
        response.addHeader("Transfer-Encoding: chunked");
        writeResponse(response, false);
        next();
    }

    @Override
    protected void processWrite() throws Exception {
        super.processWrite();
        next();
    }

    private void next() throws IOException {
        if(data == null) {
            throw new IllegalStateException("");
        }
        while (data.hasNext() && queueHead == null) {
            final Record record = data.next();
            builderChunk(record);
        }

        if(!data.hasNext()) {
            write(EMPTY_CHUNK, 0, EMPTY_CHUNK.length);
            server.incRequestsProcessed();
            if((handling = pipeline.pollFirst()) != null) {
                if(handling == FIN) {
                    scheduleClose();
                } else {
                    try {
                        server.handleRequest(handling, this);
                    } catch (IOException e) {
                        log.error("Can't handle request.",e);
                    }
                }
            }
        }
    }

    private void builderChunk(@NotNull final Record record) throws IOException {

        final byte[] key = IteratorsTool.toByteArray(record.getKey().duplicate());
        final byte[] value = IteratorsTool.toByteArray(record.getValue().duplicate());

        // <key>'\n'<value>
        final int payloadLength = key.length + DELIMITER.length + value.length;
        final String size = Integer.toHexString(payloadLength);
        // <size>\r\n<payload>\r\n
        final int chunkLength = size.length() + CRLF.length + payloadLength + CRLF.length;
        //chunk
        final byte[] chunk = new byte[chunkLength];
        final ByteBuffer buffer = ByteBuffer.wrap(chunk);

        buffer.put(size.getBytes(StandardCharsets.UTF_8));
        buffer.put(CRLF);
        buffer.put(key);
        buffer.put(DELIMITER);
        buffer.put(value);
        buffer.put(CRLF);

        write(chunk, 0, chunk.length);
    }
}
