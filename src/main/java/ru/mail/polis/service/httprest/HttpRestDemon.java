package ru.mail.polis.service.httprest;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.http.Path;
import one.nio.http.Param;
import one.nio.server.AcceptorConfig;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.service.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class HttpRestDemon extends HttpServer implements Service{

    private final DAO dao;

    public HttpRestDemon(final int port, @NotNull final DAO dao) throws IOException {
        super(createService(port));
        this.dao = dao;
    }

    /**
     * Get request by this url.
     */
    @Path("/v0/status")
    public Response status() {
        return Response.ok("OK");
    }

    /**
     * Receives a request to an entity and respond depending on the method.
     * @param id Entity id
     * @return HTTP response
     */
    @Path("/v0/entity")
    public Response entity( @Param("id") final String id, final Request request){

        if(id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, "Key is NULL".getBytes(StandardCharsets.UTF_8));
        }

        try {
            final ByteBuffer key = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
            final var method = request.getMethod();

            switch (method) {
                case Request.METHOD_GET:
                        final ByteBuffer value = dao.get(key).duplicate();
                        final byte[] response = new byte[value.duplicate().remaining()];
                        value.get(response);
                        return new Response(Response.OK,response );
                case Request.METHOD_PUT:
                    dao.upsert(key, ByteBuffer.wrap(request.getBody()));
                    return new Response(Response.CREATED, Response.EMPTY);
                case Request.METHOD_DELETE:
                    dao.remove(key);
                    return new Response(Response.ACCEPTED, Response.EMPTY);
                default:
                    return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        } catch (IOException ex) {
            return  new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private static HttpServerConfig createService(final int port) {
        final var acceptorConfig = new AcceptorConfig();
        final HttpServerConfig config = new HttpServerConfig();
        acceptorConfig.port = port;
        config.acceptors = new AcceptorConfig[]{acceptorConfig};
        return config;
    }

    @Override
    public void handleDefault(final Request request, final HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }
}

