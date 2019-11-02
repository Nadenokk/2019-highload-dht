package ru.mail.polis.service.httprest;

import one.nio.http.HttpClient;
import one.nio.http.Request;
import one.nio.http.Response;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.nadenokk.Cell;
import ru.mail.polis.dao.nadenokk.Value;
import ru.mail.polis.service.httprest.Utils.RF;
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
import one.nio.http.HttpException;
import one.nio.pool.PoolException;

class HttpController {

    private static final Logger log = LoggerFactory.getLogger(HttpController.class);
    private static final String PROXY_HEADER = "X-OK-Proxy: True";
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
        Iterator<Cell> cell = dao.lastIterator(key);
        final List<Value> responses = new ArrayList<>();
        final boolean proxyStatus = request.getHeader(PROXY_HEADER) != null;

        if (proxyStatus) {
            return ResponseTools.createResponse(ResponseTools.value(key, cell), true);
        }

        String[] poolsNodes = topology.poolsNodes(rf.from, key);
        int asks = 0;
        for (final String node : poolsNodes) {
            Response response;
            if (topology.isMe(node)) {
                Value val = ResponseTools.value(key, cell);
                responses.add(val);
                asks++;
            } else {
                try {
                    request.addHeader(PROXY_HEADER);
                    response = pools.get(node).get(ENTITY_HEADER + id, PROXY_HEADER);
                    asks++;
                    final Value val = ResponseTools.getDataFromResponse(response);
                    responses.add(val);
                } catch (HttpException | PoolException | InterruptedException e) {
                    log.info("Can not wait answer from client {} in host {}", e.getLocalizedMessage(), node);
                }
            }

        }
        if (asks >= rf.ask) {
            Value val = responses.stream().filter(Cell -> Cell.getState() != Value.State.ABSENT).
                    max(Comparator.comparingLong(Value::getTimeStamp)).orElseGet(Value::absent);
            return ResponseTools.createResponse(val, false);
        } else {
            return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
        }
    }

    Response upset(@NotNull final String id,
                   @NotNull final RF rf,
                   @NotNull final Request request) throws IOException {

        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
        final ByteBuffer byteBuffer = ByteBuffer.wrap(request.getBody());
        final boolean proxyStatus = request.getHeader(PROXY_HEADER) != null;

        if (proxyStatus) {
            dao.upsert(key, byteBuffer);
            return new Response(Response.CREATED, Response.EMPTY);
        }

        String[] poolsNodes = topology.poolsNodes(rf.from, key);
        int asks = 0;
        for (final String node : poolsNodes) {
            if (topology.isMe(node)) {
                dao.upsert(key, byteBuffer);
                asks++;
            } else {
                try {
                    final Response response = pools.get(node).put(ENTITY_HEADER + id, request.getBody(), PROXY_HEADER);
                    if (response.getStatus() == 201) {
                        asks++;
                    }
                } catch (HttpException | PoolException | InterruptedException e) {
                    log.info("Can not wait answer from client {} in host {}", e.getLocalizedMessage(), node);
                }
            }
        }
        if (asks >= rf.ask) {
            return new Response(Response.CREATED, Response.EMPTY);
        } else {
            return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
        }
    }

    Response delete(@NotNull final String id,
                    @NotNull final RF rf,
                    @NotNull final Request request) throws IOException {

        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
        final boolean proxyStatus = request.getHeader(PROXY_HEADER) != null;

        if (proxyStatus) {
            dao.remove(key);
            return new Response(Response.ACCEPTED, Response.EMPTY);
        }

        String[] poolsNodes = topology.poolsNodes(rf.from, key);
        int asks = 0;
        for (final String node : poolsNodes) {
            if (topology.isMe(node)) {
                dao.remove(key);
                asks++;
            } else {
                try {
                    final Response response = pools.get(node).delete(ENTITY_HEADER + id, PROXY_HEADER);
                    if (response.getStatus() == 202) {
                        asks++;
                    }
                } catch (HttpException | PoolException | InterruptedException e) {
                    log.info("Can not wait answer from client {} in host {}", e.getLocalizedMessage(), node);
                }
            }
        }
        if (asks >= rf.ask) {
            return new Response(Response.ACCEPTED, Response.EMPTY);
        } else {
            return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
        }
    }
}
