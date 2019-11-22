package ru.mail.polis.service.httprest;

import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.nadenokk.Value;

import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

final class FutureUtils {

    private FutureUtils() {
    }

    static CompletableFuture<List<Integer>> schedule(@NotNull final Collection<CompletableFuture<Integer>> futures,
                                                     final int ask, final int from) {
        final Lock lock = new ReentrantLock();
        final List<Integer> result = new ArrayList<>(ask);
        final CompletableFuture<List<Integer>> future = new CompletableFuture<>();
        final AtomicInteger asks = new AtomicInteger(0);
        final AtomicInteger errors = new AtomicInteger(0);
        final int asksEror = from - ask;

        futures.forEach(f -> f.whenComplete((v, exp) -> {
            if (exp != null) {
                if (errors.incrementAndGet() > asksEror) {
                    future.completeExceptionally(new RejectedExecutionException(exp));
                }
                return;
            }
            lock.lock();
            try {
                if (asks.getAndIncrement() >= ask) return;
                result.add(v);
                if (result.size() == ask) {
                    future.complete(result);
                }
            } finally {
                lock.unlock();
            }
        }).exceptionally(e -> null));
        return future;
    }

    static CompletableFuture<List<Value>> scheduleGet(@NotNull final Collection<CompletableFuture<Value>> futures,
                                                      final int ask, final int from) {

        final CompletableFuture<List<Value>> future = new CompletableFuture<>();
        final AtomicInteger errors = new AtomicInteger(0);
        final Lock lock = new ReentrantLock();
        final AtomicInteger asks = new AtomicInteger(0);
        final List<Value> result = new ArrayList<>(ask);
        final int askErors = from - ask;

        futures.forEach(f -> f.whenComplete((v, exp) -> {
            if (exp != null) {
                if (errors.incrementAndGet() > askErors) {
                    future.completeExceptionally(new RejectedExecutionException(exp));
                }
                return;
            }
            lock.lock();
            try {
                if (asks.getAndIncrement() >= ask) return;
                result.add(v);
                if (result.size() == ask) {
                    future.complete(result);
                }
            } finally {
                lock.unlock();
            }
        }).exceptionally(e -> null));
        return future;
    }

    static int asksSum(final CompletableFuture<List<Integer>> future, final int statusCode) {
        int asks = 0;
        try {
            for (final Integer status : future.get(100,TimeUnit.MILLISECONDS)) {
                if (status == statusCode) asks++;
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            return asks;
        }
        return asks;
    }

    static List<Value> getValues(@NotNull final CompletableFuture<List<Value>> future) {
        final List<Value> result = new ArrayList<>();
        try {
            result.addAll(future.toCompletableFuture().get(100,TimeUnit.MILLISECONDS));
        } catch (InterruptedException | ExecutionException | TimeoutException ex) {
            return result;
        }
        return result;
    }
}
