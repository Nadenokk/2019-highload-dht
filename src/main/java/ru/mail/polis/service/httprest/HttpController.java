package ru.mail.polis.service.httprest;

import one.nio.http.Request;
import one.nio.http.Response;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.nadenokk.Cell;
import ru.mail.polis.dao.nadenokk.Value;
import ru.mail.polis.service.httprest.utils.RF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.ArrayList;
import java.util.Comparator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

class HttpController {

    private static final Logger log = LoggerFactory.getLogger(HttpController.class);
    private static final String PROXY_HEADER_TRUE = "X-OK-Proxy: True";
    private static final String PROXY_HEADER = "X-OK-Proxy";
    private static final String ENTITY_HEADER = "/v0/entity?id=";

    private final Topology<String> topology;
    private final Map<String, HttpClient> pools;
    private final DAO dao;

    HttpController(@NotNull final DAO dao,
                   @NotNull final Map<String, HttpClient> pools,
                   @NotNull final Topology<String> topology) {
        this.dao = dao;
        this.pools = pools;
        this.topology = topology;
    }

    Response get(
            @NotNull final String id,
            @NotNull final RF rf,
            @NotNull final Request request) throws IOException, NoSuchElementException {

        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
        final Iterator<Cell> cell = dao.lastIterator(key);
        final boolean proxyStatus = request.getHeader(PROXY_HEADER_TRUE) != null;

        if (proxyStatus) {
            return ResponseTools.createResponse(ResponseTools.value(key, cell), true);
        }

        final List<Value> responses = new ArrayList<>();
        final String[] poolsNodes = topology.poolsNodes(rf.from, key);
        final AtomicInteger asks = new AtomicInteger(0);
        for (final String node : poolsNodes) {
            if (topology.isMe(node)) {
                final Value value = ResponseTools.value(key, cell);
                responses.add(value);
                asks.incrementAndGet();
            } else {
                try {
                    HttpRequest httpRequest = HttpRequest.newBuilder().uri(URI.create(node + ENTITY_HEADER + id)).
                            setHeader(PROXY_HEADER, "True").
                            timeout(Duration.ofMillis(100)).
                            GET().build();
                    CompletableFuture<HttpResponse<byte[]>> response = pools.get(node).
                            sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray());

                    final Value val = ResponseTools.getDataFromResponseAsync(response.thenApply(HttpResponse::headers).
                                    get().firstValue("X-OK-Timestamp").orElse("-1"),
                            ByteBuffer.wrap(response.thenApply(HttpResponse::body).get()),
                            response.thenApply(HttpResponse::statusCode).get());
                    responses.add(val);
                    asks.incrementAndGet();
                } catch ( InterruptedException | ExecutionException  e) {
                    log.info("Can not wait answer from client {} in host {} for Get",
                            e.getLocalizedMessage(), node);
                }
            }
        }

        if (asks.get() >= rf.ask) {
            final Value value = responses.stream().filter(Cell -> Cell.getState() != Value.State.ABSENT)
                    .max(Comparator.comparingLong(Value::getTimeStamp)).orElseGet(Value::absent);
            return ResponseTools.createResponse(value, false);
        } else {
            return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
        }
    }

    Response upset(@NotNull final String id,
                   @NotNull final RF rf,
                   @NotNull final Request request) throws IOException {

        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
        final ByteBuffer byteBuffer = ByteBuffer.wrap(request.getBody());
        final boolean proxyStatus = request.getHeader(PROXY_HEADER_TRUE) != null;

        if (proxyStatus) {
            dao.upsert(key, byteBuffer);
            return new Response(Response.CREATED, Response.EMPTY);
        }

        final String[] poolsNodes = topology.poolsNodes(rf.from, key);
        final AtomicInteger asks = new AtomicInteger(0);
        for (final String node : poolsNodes) {
            if (topology.isMe(node)) {
                dao.upsert(key, byteBuffer);
                asks.incrementAndGet();
            } else {
                try {
                    final byte[] bytes = request.getBody();
                    HttpRequest httpRequest = HttpRequest.newBuilder().
                            uri(URI.create(node + ENTITY_HEADER + id)).
                            setHeader(PROXY_HEADER, "True").timeout(Duration.ofMillis(100)).
                            PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).
                            build();
                    CompletableFuture<HttpResponse<String>> response =
                            pools.get(node).sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString());

                    if ( response.thenApply(HttpResponse::statusCode).get() == 201) {
                         asks.incrementAndGet();
                    }
                } catch ( InterruptedException | ExecutionException e) {
                    log.info("Can not wait answer from client {} in host {} for UpSet",
                            e.getLocalizedMessage(), node);
                }
            }
        }

        if (asks.get() >= rf.ask) {
            return new Response(Response.CREATED, Response.EMPTY);
        } else {
            return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
        }
    }

    Response delete(@NotNull final String id,
                    @NotNull final RF rf,
                    @NotNull final Request request) throws IOException {

        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
        final boolean proxyStatus = request.getHeader(PROXY_HEADER_TRUE) != null;

        if (proxyStatus) {
            dao.remove(key);
            return new Response(Response.ACCEPTED, Response.EMPTY);
        }

        final String[] poolsNodes = topology.poolsNodes(rf.from, key);
        final AtomicInteger  asks = new AtomicInteger(0);
        for (final String node : poolsNodes) {
            if (topology.isMe(node)) {
                dao.remove(key);
                asks.incrementAndGet();
            } else {
                try {
                    HttpRequest httpRequest = HttpRequest.newBuilder().DELETE().uri(URI.create(node + ENTITY_HEADER + id)).
                            setHeader(PROXY_HEADER, "True").timeout(Duration.ofMillis(100)).
                            build();
                    CompletableFuture<HttpResponse<String>> response =
                            pools.get(node).sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString());

                    if ( response.thenApply(HttpResponse::statusCode).get() == 202) {
                        asks.incrementAndGet();
                    }
                } catch ( InterruptedException | ExecutionException  e) {
                    log.info("Can not wait answer from client {} in host {} for Dell",
                            e.getLocalizedMessage(), node);
                }
            }
        }

        if (asks.get() >= rf.ask) {
            return new Response(Response.ACCEPTED, Response.EMPTY);
        } else {
            return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
        }
    }
}
