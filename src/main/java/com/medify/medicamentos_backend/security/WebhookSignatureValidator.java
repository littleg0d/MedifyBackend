package com.medify.medicamentos_backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * Validador de firmas de webhooks de MercadoPago según documentación oficial
 * https://www.mercadopago.com.ar/developers/es/docs/your-integrations/notifications/webhooks
 */
@Component
public class WebhookSignatureValidator {

    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureValidator.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    @Value("${webhook.secret:}")
    private String webhookSecret;

    public boolean isConfigured() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    /**
     * Valida la firma del webhook de MercadoPago
     *
     * @param xSignature Header x-signature (formato: ts=1704908010,v1=hash...)
     * @param xRequestId Header x-request-id
     * @param dataId El data.id del payload
     * @return true si la firma es válida
     */
    public boolean isValidSignature(String xSignature, String xRequestId, String dataId) {

        // VALIDACIÓN DESHABILITADA TEMPORALMENTE
        // Actualmente devolvemos true para permitir pruebas locales y evitar rechazo
        // por firma mientras se configura el webhook secret y el flujo completo.
        // Cuando se reactive, el parámetro `dataId` debe ser el campo `data.id`
        // recibido en el payload del webhook y se usará en el template:
        // "id:[data.id];request-id:[x-request-id];ts:[ts];"
        // return true;
        // 1. Validar que la configuración y los datos existan
        if (!isConfigured()) {
            log.error("Validación de firma fallida: webhook.secret no está configurado.");
            // Si no hay secret, no se puede validar.
            return false;
        }

        if (xSignature == null || xSignature.isBlank() ||
                xRequestId == null || xRequestId.isBlank() ||
                dataId == null || dataId.isBlank()) {

            log.warn("Validación de firma fallida: Faltan headers (x-signature, x-request-id) o dataId.");
            return false;
        }

        try {
            // 2. Parsear el header (equivale a signature.split(','))
            // Esto ya lo hace tu método 'parseSignatureHeader'
            Map<String, String> parts = parseSignatureHeader(xSignature);
            String ts = parts.get("ts");       // 'valueOfTimestamp' en tu JS
            String v1 = parts.get("v1"); // 'valueOfXSignature' en tu JS

            if (ts == null || v1 == null) {
                log.warn("Validación de firma fallida: Header x-signature malformado. Faltan 'ts' o 'v1'.");
                return false;
            }

            // 3. Crear el template (equivale a 'signatureTemplateParsed')
            String template = String.format("id:%s;request-id:%s;ts:%s;",
                    dataId, xRequestId, ts);

            // 4. Calcular el HMAC (equivale a crypto.createHmac)
            // Esto ya lo hace tu método 'calculateHMAC'
            String expectedSignature = calculateHMAC(template, webhookSecret);

            // 5. Comparar las firmas (equivale a valueOfXSignature == cyphedSignature)
            // Usamos MessageDigest.isEqual para una comparación segura (evita timing attacks)
            boolean isValid = MessageDigest.isEqual(
                    v1.getBytes(StandardCharsets.UTF_8),
                    expectedSignature.getBytes(StandardCharsets.UTF_8)
            );

            if (!isValid) {
                log.warn("¡Validación de firma INCORRECTA! Esperado: {}, Recibido: {}", expectedSignature, v1);
            } else {
                log.debug("Validación de firma exitosa para id: {}", dataId);
            }

            return isValid;

        } catch (Exception e) {
            log.error("Error fatal validando firma: {}", e.getMessage(), e);
            return false; // Ante cualquier error, rechazar la firma
        }
    }


    /**
     * Verifica si el timestamp del webhook no está expirado
     *
     * @param xSignature Header x-signature
     * @param maxAgeSeconds Edad máxima permitida en segundos
     * @return true si el timestamp es reciente
     */
    public boolean isRecentTimestamp(String xSignature, long maxAgeSeconds) {
        if (xSignature == null || xSignature.isBlank()) {
            return false;
        }

        try {
            Map<String, String> parts = parseSignatureHeader(xSignature);
            String tsStr = parts.get("ts");

            if (tsStr == null) {
                log.error("No se encontró timestamp en x-signature");
                return false;
            }

            long ts = Long.parseLong(tsStr);
            long now = System.currentTimeMillis() / 1000; // Convertir a segundos
            long age = now - ts;

            if (age > maxAgeSeconds) {
                log.warn("Webhook expirado. Edad: {}s, Máximo: {}s", age, maxAgeSeconds);
                return false;
            }

            log.debug("Timestamp válido. Edad: {}s", age);
            return true;

        } catch (NumberFormatException e) {
            log.error("Error parseando timestamp: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Parsea el header x-signature
     * Formato esperado: ts=1704908010,v1=618c85345248dd820d5fd456117c2ab2ef8eda45a0282ff693eac24131a5e839
     *
     * @param xSignature Header completo
     * @return Map con "ts" y "v1"
     */
    private Map<String, String> parseSignatureHeader(String xSignature) {
        Map<String, String> parts = new HashMap<>();

        if (xSignature == null || xSignature.isBlank()) {
            return parts;
        }

        // Dividir por comas: ts=...,v1=...
        String[] pairs = xSignature.split(",");

        for (String pair : pairs) {
            String[] keyValue = pair.trim().split("=", 2);
            if (keyValue.length == 2) {
                String key = keyValue[0].trim();
                String value = keyValue[1].trim();
                parts.put(key, value);
            }
        }

        return parts;
    }

    /**
     * Calcula HMAC-SHA256
     *
     * @param data Datos a firmar
     * @param secret Clave secreta
     * @return Hash hexadecimal
     */
    private String calculateHMAC(String data, String secret)
            throws NoSuchAlgorithmException, InvalidKeyException {

        Mac hmac = Mac.getInstance(HMAC_SHA256);
        SecretKeySpec secretKey = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                HMAC_SHA256
        );
        hmac.init(secretKey);

        byte[] hashBytes = hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));

        // Convertir a hexadecimal
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }

        return hexString.toString();
    }
}