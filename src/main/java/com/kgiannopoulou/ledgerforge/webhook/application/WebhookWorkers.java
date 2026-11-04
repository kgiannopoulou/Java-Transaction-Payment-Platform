package com.kgiannopoulou.ledgerforge.webhook.application;

import com.kgiannopoulou.ledgerforge.webhook.application.WebhookProperties.Dispatcher;
import com.kgiannopoulou.ledgerforge.webhook.application.WebhookProperties.WorkerMode;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Threads that make outbound webhook calls. The workload is almost pure waiting, which is what
 * virtual threads are for; {@code PLATFORM} mode exists for comparison. See
 * docs/virtual-threads.md.
 *
 * <p>Deliberately not an {@code Executor} bean: exposing one would make Spring Boot back off from
 * configuring its own application task executor.
 */
@Component
public class WebhookWorkers {

    private final ExecutorService executor;
    private final int capacity;

    public WebhookWorkers(WebhookProperties properties) {
        Dispatcher config = properties.dispatcher();
        if (config.mode() == WorkerMode.VIRTUAL) {
            this.executor = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("webhook-", 0).factory());
            this.capacity = config.maxInFlight();
        } else {
            this.executor = Executors.newFixedThreadPool(config.platformPoolSize(),
                    Thread.ofPlatform().name("webhook-", 0).daemon().factory());
            // A claim must never sit in a queue: its lease only covers one request.
            this.capacity = Math.min(config.maxInFlight(), config.platformPoolSize());
        }
    }

    /** How many deliveries can run at once. */
    public int capacity() {
        return capacity;
    }

    public void execute(Runnable task) {
        executor.execute(task);
    }

    /** Graceful: stops accepting work and waits for calls in flight, bounded by their timeouts. */
    @PreDestroy
    public void close() {
        executor.close();
    }
}
