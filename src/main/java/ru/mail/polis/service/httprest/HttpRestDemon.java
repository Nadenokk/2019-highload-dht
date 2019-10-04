package ru.mail.polis.service.httprest;

import one.nio.http.*;
import one.nio.server.AcceptorConfig;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.service.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HttpRestDemon extends HttpServer implements Service{

    private final DAO dao;
    private static final Logger log = LoggerFactory.getLogger(HttpRestDemon.class);

    public HttpRestDemon(final int port, @NotNull final DAO dao) throws IOException {
        super(createService(port));
        this.dao = dao;
    }

    @Path("/v0/status")
    public Response status() {
        return Response.ok("OK");
    }

    @Path("/v0/entity")
    public Response entity ( @Param("id") final String id, final Request request){

        try {
            if(id == null || id.isEmpty()) {
                return new Response(Response.BAD_REQUEST, "Key is NULL".getBytes(StandardCharsets.UTF_8));
            }
            final ByteBuffer key = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
            final var method = request.getMethod();

            switch (method) {
                case Request.METHOD_GET:
                    try {
                        final ByteBuffer value = dao.get(key).duplicate();
                        //log.info("Key:" + StandardCharsets.UTF_8.decode(key).toString() + " Value:" +StandardCharsets.UTF_8.decode(value.duplicate()).toString() );
                        //byte[] response = "Hello World".getBytes("Latin1");
                        byte[] response = new byte[value.duplicate().remaining()];
                        value.get(response);
                        return new Response(Response.OK,response );
                    } catch (NoSuchElementException ex) {
                        return new Response(Response.NOT_FOUND, Response.EMPTY);
                    }
                case Request.METHOD_PUT:
                    final ByteBuffer value = ByteBuffer.wrap(request.getBody());
                    //log.info("Key:" + StandardCharsets.UTF_8.decode(key.duplicate()).toString() + " Body:" +StandardCharsets.UTF_8.decode(value.duplicate()).toString() );
                    dao.upsert(key, value);

                    return new Response(Response.CREATED, Response.EMPTY);
                case Request.METHOD_DELETE:
                    dao.remove(key);
                    return new Response(Response.ACCEPTED, Response.EMPTY);
                default:
                    return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }
        catch (IOException ex) {
            return  new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private static HttpServerConfig createService(final int port) {
        //  if(port <= 1024 || port >= 65536) {
        //      throw new IllegalArgumentException("Invalid port");
        // }
        var acceptorConfig = new AcceptorConfig();
        HttpServerConfig config = new HttpServerConfig();
        acceptorConfig.port = port;
        config.acceptors = new AcceptorConfig[]{acceptorConfig};
        return config;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }
}

