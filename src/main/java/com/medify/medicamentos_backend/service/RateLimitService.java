package com.medify.medicamentos_backend.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servicio de rate limiting usando Guava Cache (in-memory)
 * Para producción multi-instancia, considerar Redis
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    // Cache con expiración automática
    private final Cache<String, AtomicInteger> requestCounts;
    private final int maxRequestsPerMinute;
    private final int maxRequestsPerHour;

    public RateLimitService() {
        this.maxRequestsPerMinute = 10;
        this.maxRequestsPerHour = 100;

        // Cache para contadores por minuto
        this.requestCounts = CacheBuilder.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();
    }

    /**
     * Verifica si una IP puede hacer una petición
     * @param identifier Identificador (IP, userId, etc.)
     * @return true si está permitido, false si excede el límite
     */
    public boolean allowRequest(String identifier) {
        return allowRequest(identifier, "global");
    }

    /**
     * Verifica rate limit con namespace específico
     * @param identifier Identificador
     * @param namespace Namespace (ej: "webhook", "api", "payment")
     * @return true si está permitido
     */
    public boolean allowRequest(String identifier, String namespace) {
        String key = namespace + ":" + identifier;

        AtomicInteger counter = requestCounts.getIfPresent(key);
        if (counter == null) {
            counter = new AtomicInteger(0);
            requestCounts.put(key, counter);
        }

        int count = counter.incrementAndGet();

        if (count > maxRequestsPerMinute) {
            log.warn("Rate limit excedido para {} en namespace {}: {} requests/min",
                    identifier, namespace, count);
            return false;
        }

        if (count == maxRequestsPerMinute - 2) {
            log.info("Acercándose al rate limit para {}: {}/{} requests",
                    identifier, count, maxRequestsPerMinute);
        }

        return true;
    }

    /**
     * Verifica rate limit específico para webhooks
     */
    public boolean allowWebhook(String paymentId) {
        // Webhooks tienen un límite más estricto (máximo 5 por minuto por payment)
        String key = "webhook:" + paymentId;

        AtomicInteger counter = requestCounts.getIfPresent(key);
        if (counter == null) {
            counter = new AtomicInteger(0);
            requestCounts.put(key, counter);
        }

        int count = counter.incrementAndGet();

        if (count > 5) {
            log.error("Rate limit de webhook excedido para payment {}: {} webhooks/min",
                    paymentId, count);
            return false;
        }

        return true;
    }

    /**
     * Obtiene el número de requests actual para un identifier
     */
    public int getCurrentCount(String identifier, String namespace) {
        String key = namespace + ":" + identifier;
        AtomicInteger counter = requestCounts.getIfPresent(key);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Limpia manualmente el contador para un identifier
     */
    public void reset(String identifier, String namespace) {
        String key = namespace + ":" + identifier;
        requestCounts.invalidate(key);
        log.info("Rate limit reseteado para {}", key);
    }

    /**
     * Limpia todos los contadores (útil para testing)
     */
    public void resetAll() {
        requestCounts.invalidateAll();
        log.info("Todos los rate limits reseteados");
    }
}