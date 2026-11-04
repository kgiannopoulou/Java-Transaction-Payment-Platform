package com.kgiannopoulou.ledgerforge.support;

import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookSignature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * A real HTTP server standing in for a customer's endpoint. It answers 200 unless scripted
 * otherwise, and records what arrived, so tests exercise the actual sockets, timeouts and headers.
 */
public final class WebhookReceiverStub implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Received(String body, String signature, String eventId, String deliveryId, int attempt) {

        @SuppressWarnings("unchecked")
        public Map<String, Object> json() {
            return JSON.readValue(body, Map.class);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Deque<Integer> scriptedStatuses = new ArrayDeque<>();
    private volatile int defaultStatus = 200;
    private volatile Duration delay = Duration.ZERO;
    private volatile Duration bodyStall = Duration.ZERO;

    public WebhookReceiverStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4096);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/webhooks";
    }

    /** Answers with these statuses in order, then {@code 200}. */
    public synchronized void respondWith(Integer... statuses) {
        scriptedStatuses.addAll(List.of(statuses));
    }

    public void alwaysRespondWith(int status) {
        defaultStatus = status;
    }

    /** Holds every response back this long. */
    public void delayResponses(Duration delay) {
        this.delay = delay;
    }

    /** Sends 200 headers at once, then keeps the connection open without finishing the body. */
    public void stallResponseBody(Duration stall) {
        this.bodyStall = stall;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var headers = exchange.getRequestHeaders();
            received.add(new Received(body, headers.getFirst(WebhookSignature.HEADER),
                    headers.getFirst("LedgerForge-Event-Id"), headers.getFirst("LedgerForge-Delivery-Id"),
                    Integer.parseInt(headers.getFirst("LedgerForge-Attempt"))));
            if (!delay.isZero()) {
                Thread.sleep(delay);
            }
            if (!bodyStall.isZero()) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                Thread.sleep(bodyStall);
                return;
            }
            exchange.sendResponseHeaders(nextStatus(), -1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized int nextStatus() {
        Integer scripted = scriptedStatuses.poll();
        return scripted != null ? scripted : defaultStatus;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
