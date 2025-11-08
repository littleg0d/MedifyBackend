package com.medify.medicamentos_backend.util;

import java.util.Map;

/**
 * Utilidades para extraer informaciÃ³n de payloads de webhooks
 */
public class WebhookPayloadUtils {

    private WebhookPayloadUtils() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Extrae el payment ID del payload del webhook de MercadoPago
     */
    public static String extractPaymentId(Map<String, Object> payload) {
        Object data = payload.get("data");
        if (data instanceof Map) {
            Object id = ((Map<?, ?>) data).get("id");
            return id != null ? id.toString() : null;
        }
        return null;
    }

    /**
     * Extrae un valor String del mapa
     */
    public static String extractString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }
}