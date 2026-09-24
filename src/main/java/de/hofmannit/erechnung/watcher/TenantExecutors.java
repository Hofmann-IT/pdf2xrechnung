package de.hofmannit.erechnung.watcher;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import de.hofmannit.erechnung.configuration.AppProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sequentielle Verarbeitung je Mandant (ein Thread je Mandant), konfigurierbare Parallelität
 * zwischen Mandanten über eine Semaphore (Vorgabe Abschnitt 37).
 */
@Component
public class TenantExecutors {

    private static final Logger log = LoggerFactory.getLogger(TenantExecutors.class);

    private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();
    private final Semaphore parallelism;

    public TenantExecutors(AppProperties properties) {
        int n = Math.max(1, properties.processing().tenantParallelism());
        this.parallelism = new Semaphore(n, true);
    }

    public void submit(String tenantId, Runnable task) {
        executors.computeIfAbsent(tenantId, id -> Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "processing-" + id);
            t.setDaemon(true);
            return t;
        })).submit(() -> {
            try {
                parallelism.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Unbehandelter Fehler in der Verarbeitung für Mandant {}", tenantId, e);
            } finally {
                parallelism.release();
            }
        });
    }

    public void shutdown() {
        executors.values().forEach(ExecutorService::shutdown);
        for (ExecutorService es : executors.values()) {
            try {
                if (!es.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.warn("Verarbeitung wurde nach 60 s nicht beendet; laufende Runs werden beim Neustart wiederaufgenommen");
                    es.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        executors.clear();
    }
}
