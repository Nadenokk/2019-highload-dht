package ru.mail.polis.service.httprest.utils;

import org.jetbrains.annotations.NotNull;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.net.URI;

public class CreateHttpRequest {

    private static final String PROXY_HEADER = "X-OK-Proxy";
    private static final String ENTITY_HEADER = "/v0/entity?id=";

    private CreateHttpRequest(){}

    //public static HttpRequest createGet(){
   //
   // }

    public static HttpRequest createUpset(@NotNull final String node, @NotNull final String id,
                                          final byte[] bytes){
        return HttpRequest.newBuilder()
                        .uri(URI.create(node + ENTITY_HEADER + id))
                        .setHeader(PROXY_HEADER, "True").timeout(Duration.ofMillis(100))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build();
    }

    @NotNull
    public static HttpRequest creteDelete(@NotNull final String node, @NotNull final String id){
        return HttpRequest.newBuilder().DELETE()
                        .uri(URI.create(node + ENTITY_HEADER + id))
                        .setHeader(PROXY_HEADER, "True").timeout(Duration.ofMillis(100))
                        .build();
    }
}
