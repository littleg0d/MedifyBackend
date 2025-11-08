package com.medify.medicamentos_backend.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Validador de firmas de webhooks de MercadoPago
 * Documentación: https://www.mercadopago.com.ar/developers/es/docs/your-integrations/notifications/webhooks
 */
@Component
public class WebhookSignatureValidator {

    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureValidator.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    @Value("${mercadopago.webhook.secret:}")
    private String webhookSecret;

    /**
     * Valida la firma del webhook de MercadoPago
     *
     * @param xSignature Header "x-signature" del webhook
     * @param xRequestId Header "x-request-id" del webhook
     * @param dataId ID del recurso notificado (payment ID)
     * @return true si la firma es válida
     */
    public boolean isValidSignature(String xSignature, String xRequestId, String dataId) {

        if (!isConfigured()) {
            log.warn("Webhook secret no configurado. Validación de firma deshabilitada.");
            return true; // En desarrollo, permitir sin validación
        }

        if (xSignature == null || xSignature.isEmpty()) {
            log.error("Header x-signature no presente en el webhook");
            return false;
        }

        if (xRequestId == null || xRequestId.isEmpty()) {
            log.error("Header x-request-id no presente en el webhook");
            return false;
        }

        try {
            // Extraer partes del header x-signature
            // Formato: "ts=1234567890,v1=hash_value"
            String[] parts = xSignature.split(",");
            String ts = null;
            String hash = null;

            for (String part : parts) {
                String[] keyValue = part.trim().split("=", 2);
                if (keyValue.length == 2) {
                    if ("ts".equals(keyValue[0])) {
                        ts = keyValue[1];
                    } else if ("v1".equals(keyValue[0])) {
                        hash = keyValue[1];
                    }
                }
            }

            if (ts == null || hash == null) {
                log.error("Formato de x-signature inválido: {}", xSignature);
                return false;
            }

            // Construir el manifest según la documentación de MP
            // manifest = "id:{dataId};request-id:{xRequestId};ts:{ts};"
            String manifest = String.format("id:%s;request-id:%s;ts:%s;", dataId, xRequestId, ts);

            // Calcular HMAC SHA256
            String calculatedHash = calculateHMAC(manifest);

            // Comparar hashes
            boolean valid = hash.equals(calculatedHash);

            if (!valid) {
                log.error("Firma de webhook inválida. Esperado: {}, Recibido: {}", calculatedHash, hash);
                log.debug("Manifest usado: {}", manifest);
            } else {
                log.debug("Firma de webhook validada correctamente");
            }

            return valid;

        } catch (Exception e) {
            log.error("Error validando firma del webhook: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Valida que el timestamp no sea muy antiguo (previene replay attacks)
     *
     * @param xSignature Header "x-signature" que contiene el timestamp
     * @param maxAgeSeconds Edad máxima permitida en segundos (default: 300 = 5 minutos)
     * @return true si el timestamp es reciente
     */
    public boolean isRecentTimestamp(String xSignature, long maxAgeSeconds) {
        try {
            String[] parts = xSignature.split(",");
            for (String part : parts) {
                String[] keyValue = part.trim().split("=", 2);
                if (keyValue.length == 2 && "ts".equals(keyValue[0])) {
                    long webhookTimestamp = Long.parseLong(keyValue[1]);
                    long currentTimestamp = System.currentTimeMillis() / 1000;
                    long age = currentTimestamp - webhookTimestamp;

                    if (age > maxAgeSeconds) {
                        log.warn("Webhook demasiado antiguo: {} segundos de edad", age);
                        return false;
                    }

                    if (age < -60) { // Timestamp en el futuro (con 1 minuto de tolerancia)
                        log.warn("Webhook con timestamp futuro: {} segundos adelantado", -age);
                        return false;
                    }

                    return true;
                }
            }

            log.error("No se encontró timestamp en x-signature");
            return false;

        } catch (Exception e) {
            log.error("Error validando timestamp: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Verifica si el validador está configurado
     */
    public boolean isConfigured() {
        return webhookSecret != null && !webhookSecret.isEmpty();
    }

    /**
     * Calcula el HMAC SHA256 del manifest con la clave secreta
     */
    private String calculateHMAC(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8),
                    HMAC_SHA256
            );
            mac.init(secretKeySpec);

            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

            // Convertir a hexadecimal
            StringBuilder hexString = new StringBuilder();
            for (byte b : hmacBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Error calculando HMAC: " + e.getMessage(), e);
        }
    }
}