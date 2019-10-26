package ru.mail.polis.service.httprest;

import one.nio.http.HttpServer;
import one.nio.http.HttpClient;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.http.Path;
import one.nio.http.Param;
import one.nio.net.ConnectionString;
import one.nio.net.Socket;
import one.nio.server.AcceptorConfig;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.service.Service;
import ru.mail.polis.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;

import one.nio.http.HttpException;
import one.nio.pool.PoolException;


public final class HttpRestDemon extends HttpServer implements Service {

    private static final Logger log = LoggerFactory.getLogger(HttpRestDemon.class);

    private final Topology<String> topology;
    private final Map<String, HttpClient> pools;

    private final DAO dao;

    public HttpRestDemon(final int port, @NotNull final DAO dao,
                         @NotNull final Topology<String> topology) throws IOException {
        super(createService(port));
        this.dao = dao;
        this.topology = topology;

        this.pools = new HashMap<>();
        for (final String host : topology.all()) {
            if (topology.isMe(host)) {
                log.info("We process int host : {}", host);
                continue;
            }
            log.info("We have next host in the pool : {}", host);
            assert !pools.containsKey(host);
            pools.put(host, new HttpClient(new ConnectionString(host + "?timeout=100")));
        }
    }

    /**
     * Get request by this url.
     */
    @NotNull
    @Path("/v0/status")
    public Response status() {
        return Response.ok("OK");
    }


    /**
     * Rest-endpoint with this uri.
     *
     * @param start   is parameters for uri
     * @param end     is parameters for uri
     * @param request is request on this uri
     * @param session is current session
     */
    @Path("/v0/entities")
    public void entities(@Param("start") final String start,
                         @Param("end") final String end, @NotNull final Request request,
                         @NotNull final HttpSession session) {
        if (end != null && end.isEmpty()){
            ResponseUtils.sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }
        if (start == null || start.isEmpty()){
            ResponseUtils.sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }
        if (request.getMethod() != Request.METHOD_GET) {
            ResponseUtils.sendResponse(session, new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
            return;
        }
        try {
            final Iterator<Record> recordIterator = dao.range(ByteBuffer.wrap(start.getBytes(StandardCharsets.UTF_8)),
                    end == null ? null : ByteBuffer.wrap(end.getBytes(StandardCharsets.UTF_8)));
            ((StorageSession) session).stream(recordIterator);
        } catch (IOException e) {
            log.error("Something wrong while get range of value", e);
        }
    }

    /**
     * Receives a request to an entity and respond depending on the method.
     *
     * @param id      Entity id
     * @param session is current session
     * @param request is request on this uri
     */
    @Path("/v0/entity")
    public void entity(@Param("id") final String id, final Request request, final HttpSession session) {
        if (id == null || id.isEmpty()) {
            ResponseUtils.sendResponse(session,
                    new Response(Response.BAD_REQUEST, "Key is NULL".getBytes(StandardCharsets.UTF_8)));
            return;
        }

        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
        final String node = topology.primaryFor(key);

        if (!topology.isMe(node)) {
            log.debug("print proxy:"+node+" "+topology.isMe(node)+" "+request);
            asyncExecute(session, () -> proxy(node, request));
            return;
        }
        log.debug("print locol:"+node+" "+topology.isMe(node)+" "+request);
        createResponse(request, key, session);
    }

    private Response proxy(@NotNull final String node, @NotNull final Request request) throws IOException {
        assert !topology.isMe(node);
        try {
            return pools.get(node).invoke(request);
        } catch (InterruptedException | PoolException | HttpException e) {
            throw new IOException("Can't proxy", e);
        }
    }


    private void createResponse(@NotNull final Request request,
                                @NotNull final ByteBuffer key, final HttpSession session) {
            final var method = request.getMethod();
            switch (method) {
                case Request.METHOD_GET:
                        asyncExecute(session, () -> get(key));
                    break;
                case Request.METHOD_PUT:
                        asyncExecute(session, () -> upset(key, request.getBody()));
                    break;
                case Request.METHOD_DELETE:
                        asyncExecute(session, () -> delete(key));
                    break;
                default:
                    asyncExecute(session, () -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
                    break;
            }
    }

    private Response get(
            @NotNull final ByteBuffer key) throws IOException, NoSuchElementException {
        final ByteBuffer value = dao.get(key).duplicate();
        final byte[] response = new byte[value.duplicate().remaining()];
        value.get(response);
        return new Response(Response.OK, response);
    }

    private Response upset(@NotNull final ByteBuffer key, @NotNull final byte[] value) throws IOException {
        dao.upsert(key, ByteBuffer.wrap(value));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    private Response delete(@NotNull final ByteBuffer key) throws IOException {
        dao.remove(key);
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }


    private void asyncExecute(@NotNull final HttpSession session, @NotNull final ResponsePublisher publisher) {
        asyncExecute(() -> {
            try {
                ResponseUtils.sendResponse(session, publisher.submit());
            } catch (IOException e) {
                log.error("Unable to create response", e);
                try {
                    session.sendError(Response.INTERNAL_ERROR, "Error while send response");
                } catch (IOException ioException) {
                    log.error("Error while send response {}", ioException.getMessage());
                }
            } catch (NoSuchElementException e) {
                try {
                    session.sendError(Response.NOT_FOUND, "Not found recourse!");
                } catch (IOException ex) {
                    log.error("Error while send error", ex);
                }
            }
        });
    }

    private static HttpServerConfig createService(final int port) {
        final var acceptorConfig = new AcceptorConfig();
        final HttpServerConfig config = new HttpServerConfig();
        acceptorConfig.port = port;
        config.acceptors = new AcceptorConfig[]{acceptorConfig};
        config.minWorkers = Runtime.getRuntime().availableProcessors();
        config.maxWorkers = Runtime.getRuntime().availableProcessors();
        return config;
    }

    @Override
    public void handleDefault(final Request request, final HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }

    @Override
    public HttpSession createSession(@NotNull final Socket socket) {
        return new StorageSession(socket, this);
    }

    @FunctionalInterface
    private interface ResponsePublisher {
        Response submit() throws IOException;
    }

    private static final class ResponseUtils {
        private ResponseUtils() {
        }

        private static void sendResponse(@NotNull final HttpSession session,
                                         @NotNull final Response response) {
            try {
                session.sendResponse(response);
            } catch (IOException e) {
                try {
                    session.sendError(Response.INTERNAL_ERROR, "Error while send response");
                } catch (IOException ex) {
                    log.error("Error while send error", ex);
                }
            }
        }
    }
}
