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
import ru.mail.polis.service.httprest.utils.RF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;

public final class HttpRestDemon extends HttpServer implements Service {

    private static final Logger log = LoggerFactory.getLogger(HttpRestDemon.class);

    private final DAO dao;
    private final HttpController httpController;
    private final int nodesSize;

    /**
     * Async service.
     *
     * @param port     number of a port.
     * @param dao      LSMDao.
     * @param topology is pool of workers
     * @throws IOException throw exception.
     */
    public HttpRestDemon(final int port, @NotNull final DAO dao,
                         @NotNull final Topology<String> topology) throws IOException {
        super(createService(port));
        this.dao = dao;

        final Map<String, HttpClient> pools = new HashMap<>();
        for (final String host : topology.all()) {
            if (topology.isMe(host)) {
                log.info("We process int host : {}", host);
                continue;
            }
            log.info("We have next host in the pool : {}", host);
            assert !pools.containsKey(host);
            pools.put(host, new HttpClient(new ConnectionString(host + "?timeout=100")));
        }
        this.nodesSize = pools.size() +1 ;
        this.httpController = new HttpController(dao, pools, topology);
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
     * @param replicas Entity ask and form
     * @param session is current session
     * @param request is request on this uri
     */
    @Path("/v0/entity")
    public void entity(@Param("id") final String id,
                       @Param("replicas") final String replicas,
                       final Request request,
                       final HttpSession session) {
        if (id == null || id.isEmpty()) {
            ResponseUtils.sendResponse(session,
                    new Response(Response.BAD_REQUEST, "Key is NULL".getBytes(StandardCharsets.UTF_8)));
            return;
        }

        final RF rf ;
        try {
            rf = RF.of(replicas,nodesSize);
            if(rf.ask < 1 || rf.from < rf.ask || rf.from > nodesSize) {
                throw new IllegalArgumentException("Replicas is BAD!");
            }
        } catch (IllegalArgumentException e) {
            ResponseUtils.sendResponse(session, new Response(Response
                    .BAD_REQUEST, "Replicas is BAD".getBytes(StandardCharsets.UTF_8)));
            return;
        }

        final var method = request.getMethod();
        switch (method) {
            case Request.METHOD_GET:
                asyncExecute(session, () -> httpController.get(id,rf,request));
                break;
            case Request.METHOD_PUT:
                asyncExecute(session, () -> httpController.upset(id, rf,request));
                break;
            case Request.METHOD_DELETE:
                asyncExecute(session, () -> httpController.delete(id,rf,request));
                break;
            default:
                asyncExecute(session, () -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
                break;
        }
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
