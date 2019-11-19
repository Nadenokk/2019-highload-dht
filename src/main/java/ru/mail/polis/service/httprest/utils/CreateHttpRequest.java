package ru.mail.polis.service.httprest.utils;

import org.jetbrains.annotations.NotNull;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.net.URI;

public class CreateHttpRequest {

    private static final String PROXY_HEADER = "X-OK-Proxy";
    private static final String ENTITY_HEADER = "/v0/entity?id=";

    private CreateHttpRequest() {
    }

    /**
     * @param node is Node
     * @param id   is ID
     * @return request for Get
     */
    @NotNull
    public static HttpRequest createGet(@NotNull final String node, @NotNull final String id) {
        return HttpRequest.newBuilder().uri(URI.create(node + ENTITY_HEADER + id))
                .setHeader(PROXY_HEADER, "True")
                .timeout(Duration.ofMillis(100))
                .GET().build();
    }

    /**
     * @param node  is node
     * @param id    is id
     * @param bytes is Data
     * @return request for UpSet
     */
    @NotNull
    public static HttpRequest createUpset(@NotNull final String node, @NotNull final String id,
                                          final byte[] bytes) {
        return HttpRequest.newBuilder()
                .uri(URI.create(node + ENTITY_HEADER + id))
                .setHeader(PROXY_HEADER, "True").timeout(Duration.ofMillis(100))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
    }

    /**
     * @param node is node
     * @param id   is id
     * @return request for Delelete
     */
    @NotNull
    public static HttpRequest creteDelete(@NotNull final String node, @NotNull final String id) {
        return HttpRequest.newBuilder().DELETE()
                .uri(URI.create(node + ENTITY_HEADER + id))
                .setHeader(PROXY_HEADER, "True").timeout(Duration.ofMillis(100))
                .build();
    }
}
