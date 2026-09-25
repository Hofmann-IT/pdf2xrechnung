package de.hofmannit.erechnung.watcher;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import de.hofmannit.erechnung.configuration.RuntimeSettings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sequentielle Verarbeitung je Mandant (ein Thread je Mandant), konfigurierbare Parallelität
 * zwischen Mandanten (Vorgabe Abschnitt 37). Die Parallelität folgt Änderungen aus der
 * Verwaltung ohne Neustart (ADR 0012); laufende Verarbeitungen werden nicht unterbrochen.
 */
@Component
public class TenantExecutors {

    private static final Logger log = LoggerFactory.getLogger(TenantExecutors.class);

    private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition slotFree = lock.newCondition();
    private int permits;
    private int active;

    public TenantExecutors(RuntimeSettings settings) {
        this.permits = Math.max(1, settings.current().processing().tenantParallelism());
        settings.addListener(c -> setParallelism(c.processing().tenantParallelism()));
    }

    /** Neue Obergrenze; wirkt für alle Verarbeitungen, die noch auf einen Platz warten. */
    public void setParallelism(int n) {
        lock.lock();
        try {
            int value = Math.max(1, n);
            if (value != permits) {
                log.info("Parallelität zwischen Mandanten: {} → {}", permits, value);
                permits = value;
                slotFree.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    public int parallelism() {
        lock.lock();
        try {
            return permits;
        } finally {
            lock.unlock();
        }
    }

    public void submit(String tenantId, Runnable task) {
        executors.computeIfAbsent(tenantId, id -> Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "processing-" + id);
            t.setDaemon(true);
            return t;
        })).submit(() -> {
            try {
                acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Unbehandelter Fehler in der Verarbeitung für Mandant {}", tenantId, e);
            } finally {
                release();
            }
        });
    }

    private void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (active >= permits) {
                slotFree.await();
            }
            active++;
        } finally {
            lock.unlock();
        }
    }

    private void release() {
        lock.lock();
        try {
            active--;
            slotFree.signalAll();
        } finally {
            lock.unlock();
        }
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
