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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
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

        final AtomicInteger asks = new AtomicInteger(0);
        final AtomicInteger asksFalse = new AtomicInteger(0);
        final List<Value> responses = new ArrayList<>(rf.from);
        futures.forEach(f -> {
            if (rf.ask > rf.from - asksFalse.get()) return;
            try {
                final Value value = f.get();
                if (value == null) {
                    asksFalse.getAndIncrement();
                } else {
                    responses.add(value);
                    asks.getAndIncrement();
                }
            } catch (InterruptedException | ExecutionException e) {
                asksFalse.getAndIncrement();
            }
        });

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
                final CompletableFuture<Integer> future = CompletableFuture.runAsync(() -> {
                    try {
                        dao.upsert(key, byteBuffer);
                    } catch (IOException e) {
                        log.info("Error UpSet ");
                    }
                }, executorService).handle((s, t) -> (t == null) ? 201 : -1);
                futures.add(future);
            } else {
                final CompletableFuture<Integer> response =
                        pools.get(node).sendAsync(CreateHttpRequest.createUpset(node, id, bytes),
                                HttpResponse.BodyHandlers.discarding()).
                                handle((a, exp) -> a.statusCode());
                futures.add(response);
            }
        }

        final AtomicInteger asks = new AtomicInteger(0);
        final AtomicInteger asksFalse = new AtomicInteger(0);
        futures.forEach(f -> {
            if (rf.ask > rf.from - asksFalse.get()) return;
            try {
                if (f.get() == 201) {
                    asks.getAndIncrement();
                } else {
                    asksFalse.getAndIncrement();
                }
            } catch (InterruptedException | ExecutionException e) {
                asksFalse.getAndIncrement();
            }
        });

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
        final Collection<CompletableFuture<Integer>> futures = new ConcurrentLinkedQueue<>();
        for (final String node : poolsNodes) {
            if (topology.isMe(node)) {
                final CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        dao.remove(key);
                    } catch (IOException e) {
                        log.info("Error for Remove");
                    }
                    return null;
                }, executorService).handle((s, t) -> (t == null) ? 202 : -1);
                futures.add(future);
            } else {
                final CompletableFuture<Integer> response =
                        pools.get(node).sendAsync(CreateHttpRequest.creteDelete(node, id),
                                HttpResponse.BodyHandlers.discarding()).
                                handle((a, exp) -> a.statusCode());
                futures.add(response);
            }
        }

        final AtomicInteger asks = new AtomicInteger(0);
        final AtomicInteger asksFalse = new AtomicInteger(0);
        futures.forEach(f -> {
            if (asks.get() > rf.from - asksFalse.get()) return;
            try {
                if (f.get() == 202) {
                    asks.getAndIncrement();
                } else {
                    asksFalse.getAndIncrement();
                }
            } catch (InterruptedException | ExecutionException e) {
                asksFalse.getAndIncrement();
            }
        });
        if (asks.get() >= rf.ask) {
            return new Response(Response.ACCEPTED, Response.EMPTY);
        } else {
            return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
        }
    }
}
