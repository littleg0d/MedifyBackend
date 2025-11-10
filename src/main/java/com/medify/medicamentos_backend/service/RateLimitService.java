package com.medify.medicamentos_backend.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servicio de rate limiting usando Guava Cache (in-memory)
 * Para producción multi-instancia, considerar Redis
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final Cache<String, AtomicInteger> requestCounts;
    private final int maxRequestsPerMinute;
    private final int webhookMaxRequestsPerMinute;

    public RateLimitService(
            @Value("${rate-limit.requests-per-minute:10}") int maxRequestsPerMinute,
            @Value("${rate-limit.webhook.requests-per-minute:5}") int webhookMaxRequestsPerMinute) {

        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.webhookMaxRequestsPerMinute = webhookMaxRequestsPerMinute;

        // Cache para contadores por minuto
        this.requestCounts = CacheBuilder.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();

        log.info("RateLimitService inicializado - Max requests/min: {}, Webhook max: {}",
                maxRequestsPerMinute, webhookMaxRequestsPerMinute);
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
     * Verifica rate limit específico para webhooks (límite más estricto)
     */
    public boolean allowWebhook(String paymentId) {
        String key = "webhook:" + paymentId;

        AtomicInteger counter = requestCounts.getIfPresent(key);
        if (counter == null) {
            counter = new AtomicInteger(0);
            requestCounts.put(key, counter);
        }

        int count = counter.incrementAndGet();

        if (count > webhookMaxRequestsPerMinute) {
            log.error("Rate limit de webhook excedido para payment {}: {} webhooks/min (max: {})",
                    paymentId, count, webhookMaxRequestsPerMinute);
            return false;
        }

        return true;
    }
}