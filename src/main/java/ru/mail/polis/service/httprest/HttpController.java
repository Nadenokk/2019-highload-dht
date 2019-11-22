package ru.mail.polis.service.httprest;

import one.nio.http.Request;
import one.nio.http.Response;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.nadenokk.Cell;
import ru.mail.polis.dao.nadenokk.Value;
import ru.mail.polis.service.httprest.utils.RF;
import ru.mail.polis.service.httprest.utils.CreateHttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Comparator;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

class HttpController {

    private static final Logger log = LoggerFactory.getLogger(HttpController.class);
    private static final String PROXY_HEADER_TRUE = "X-OK-Proxy: True";

    private final Topology<String> topology;
    private final Map<String, HttpClient> pools;
    private final DAO dao;
    private final ExecutorService executorService;

    HttpController(@NotNull final DAO dao,
                   @NotNull final Map<String, HttpClient> pools,
                   @NotNull final Topology<String> topology) {
        this.dao = dao;
        this.pools = pools;
        this.topology = topology;
        this.executorService = Executors.newFixedThreadPool(3);
    }

    Response get(
            @NotNull final String id,
            @NotNull final RF rf,
            @NotNull final Request request) throws NoSuchElementException {

        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
        final Iterator<Cell> cell = dao.lastIterator(key);
        final boolean proxyStatus = request.getHeader(PROXY_HEADER_TRUE) != null;

        if (proxyStatus) {
            return ResponseTools.createResponse(ResponseTools.value(key, cell), true);
        }

        final String[] poolsNodes = topology.poolsNodes(rf.from, key);
        final Collection<CompletableFuture<Value>> futures = new ConcurrentLinkedQueue<>();
        for (final String node : poolsNodes) {
            if (topology.isMe(node)) {
                final CompletableFuture<Value> future = CompletableFuture.supplyAsync(() -> ResponseTools.value(key, cell));
                futures.add(future);
            } else {
                final CompletableFuture<Value> response = pools.get(node)
                        .sendAsync(CreateHttpRequest.createGet(node, id), HttpResponse.BodyHandlers.ofByteArray()).
                                thenApply(Value::getDataFromResponseAsync);
                futures.add(response);
            }
        }

        final CompletableFuture<List<Value>> future = FutureUtils.scheduleGet(futures, rf.ask, rf.from);
        final List<Value> responses = FutureUtils.getValues(future);
        if (responses.size() >= rf.ask) {
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
        final byte[] bytes = request.getBody();
        final ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        final boolean proxyStatus = request.getHeader(PROXY_HEADER_TRUE) != null;

        if (proxyStatus) {
            dao.upsert(key, byteBuffer);
            return new Response(Response.CREATED, Response.EMPTY);
        }

        final String[] poolsNodes = topology.poolsNodes(rf.from, key);
        final Collection<CompletableFuture<Integer>> futures = new ConcurrentLinkedQueue<>();
        for (final String node : poolsNodes) {
            if (topology.isMe(node)) {
                final CompletableFuture<Integer> future = CompletableFuture.runAsync(() -> upsetDao(key, byteBuffer),
                        executorService).handle((s, t) -> (t != null) ? -1 : 201);
                futures.add(future);
            } else {
                final CompletableFuture<Integer> response =
                        pools.get(node).sendAsync(CreateHttpRequest.createUpset(node, id, bytes),
                                HttpResponse.BodyHandlers.discarding()).
                                handle((a, exp) -> a.statusCode());
                futures.add(response);
            }
        }

        final CompletableFuture<List<Integer>> future = FutureUtils.schedule(futures, rf.ask, rf.from);
        if (FutureUtils.asksSum(future, 201) >= rf.ask) {
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
        final Collection<CompletableFuture<Integer>> futures = new ConcurrentLinkedQueue<>();
        for (final String node : poolsNodes) {
            if (topology.isMe(node)) {
                final CompletableFuture<Integer> future = CompletableFuture.runAsync(() -> deleteDao(key),
                        executorService).handle((s, t) -> (t != null) ? -1 : 202);
                futures.add(future);
            } else {
                final CompletableFuture<Integer> response =
                        pools.get(node).sendAsync(CreateHttpRequest.creteDelete(node, id),
                                HttpResponse.BodyHandlers.discarding()).
                                handle((a, exp) -> a.statusCode());
                futures.add(response);
            }
        }

        final CompletableFuture<List<Integer>> future = FutureUtils.schedule(futures, rf.ask, rf.from);
        if (FutureUtils.asksSum(future, 202) >= rf.ask) {
            return new Response(Response.ACCEPTED, Response.EMPTY);
        } else {
            return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
        }
    }

    private void deleteDao(@NotNull final ByteBuffer key) {
        try {
            dao.remove(key);
        } catch (IOException e) {
            log.info("Error UpSet ");
        }
    }

    private void upsetDao(@NotNull final ByteBuffer key, final ByteBuffer byteBuffer) {
        try {
            dao.upsert(key, byteBuffer);
        } catch (IOException e) {
            log.info("Error UpSet ");
        }
    }
}
