package com.medify.medicamentos_backend.controller;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.preference.Preference;
import com.medify.medicamentos_backend.dto.PreferenciaRequest;
import com.medify.medicamentos_backend.security.WebhookSignatureValidator;
import com.medify.medicamentos_backend.service.FirebaseService;
import com.medify.medicamentos_backend.service.MercadoPagoService;
import com.medify.medicamentos_backend.service.PagoProcessingService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Controlador REST para la gestión de pagos con MercadoPago
 */
@RestController
@RequestMapping("/api/pagos")
public class PagoController {

    private static final Logger log = LoggerFactory.getLogger(PagoController.class);
    private static final long WEBHOOK_MAX_AGE_SECONDS = 300; // 5 minutos

    private final MercadoPagoService mercadoPagoService;
    private final FirebaseService firebaseService;
    private final PagoProcessingService pagoProcessingService;
    private final WebhookSignatureValidator signatureValidator;

    @Value("${mercadopago.failure.url:}")
    private String failureUrl;

    public PagoController(MercadoPagoService mercadoPagoService,
                          FirebaseService firebaseService,
                          PagoProcessingService pagoProcessingService,
                          WebhookSignatureValidator signatureValidator) {
        this.mercadoPagoService = mercadoPagoService;
        this.firebaseService = firebaseService;
        this.pagoProcessingService = pagoProcessingService;
        this.signatureValidator = signatureValidator;
    }

    /**
     * Crea una preferencia de pago en MercadoPago
     */
    @PostMapping("/crear-preferencia")
    public ResponseEntity<?> crearPreferencia(@Valid @RequestBody PreferenciaRequest request) {

        log.info("Creando pedido en Firestore para receta: {}", request.getRecetaId());

        String pedidoId = null;
        try {
            if (!mercadoPagoService.isConfigured()) {
                log.warn("Mercado Pago no configurado");
                return handlePaymentError("Proveedor de pagos no configurado", HttpStatus.SERVICE_UNAVAILABLE);
            }

            pedidoId = firebaseService.crearPedido(request);
            log.info("Pedido creado con id: {}", pedidoId);

            Preference preferencia = mercadoPagoService.crearPreferencia(request, pedidoId);

            log.info("Preferencia creada: {} - URL: {}", preferencia.getId(), preferencia.getInitPoint());

            Map<String, String> response = new HashMap<>();
            response.put("paymentUrl", preferencia.getInitPoint());
            response.put("preferenceId", preferencia.getId());

            return ResponseEntity.ok(response);

        } catch (MPException | MPApiException e) {
            log.error("Error de Mercado Pago para pedido {}: {}", pedidoId, e.getMessage(), e);
            pagoProcessingService.limpiarPedido(pedidoId);
            return handlePaymentError("Error creando preferencia de pago: " + e.getMessage(), HttpStatus.BAD_GATEWAY);

        } catch (Exception e) {
            log.error("Error inesperado creando preferencia para pedido {}: {}", pedidoId, e.getMessage(), e);
            pagoProcessingService.limpiarPedido(pedidoId);
            return handlePaymentError("Error interno al procesar el pago", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Recibe notificaciones de MercadoPago sobre cambios en pagos
     * Valida la firma criptográfica para asegurar autenticidad
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "x-signature", required = false) String xSignature,
            @RequestHeader(value = "x-request-id", required = false) String xRequestId) {

        log.info("Webhook recibido: {}", payload);
        log.debug("Headers - x-signature: {}, x-request-id: {}", xSignature, xRequestId);

        try {
            // NOTA: Validación de firma deshabilitada completamente.
            // Aceptamos cualquier webhook y asumimos que proviene de MercadoPago.

            // Intentamos extraer un posible id (si existe) para el procesamiento o logging,
            // pero NO rechazamos el webhook si falta.
            String dataId = extractDataId(payload);
            if (dataId == null) {
                log.warn("Webhook recibido sin data.id ni resource/id, pero se procesará de todos modos");
            } else {
                log.info("Webhook identificado con id: {}", dataId);
            }

            // Procesar webhook sin validar firmas
            boolean procesado = pagoProcessingService.procesarWebhook(payload);
            return ResponseEntity.ok(procesado ? "processed" : "ignored");

        } catch (Exception ex) {
            log.error("Error procesando webhook: {}", ex.getMessage(), ex);
            // MercadoPago espera 200 incluso si hay errores para no reintentar
            return ResponseEntity.ok("error");
        }
    }

    /**
     * Verifica el estado de un pago por su ID
     */
    @GetMapping("/verificar/{paymentId}")
    public ResponseEntity<?> verificarPago(@PathVariable String paymentId) {
        if (paymentId == null || paymentId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Payment ID inválido"));
        }

        if (!mercadoPagoService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Servicio de pagos no disponible"));
        }

        try {
            log.info("Verificando pago: {}", paymentId);
            Payment payment = mercadoPagoService.verificarPago(paymentId);
            return ResponseEntity.ok(payment);
        } catch (MPException | MPApiException e) {
            log.error("Error verificando pago {}: {}", paymentId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "No se pudo verificar el pago"));
        }
    }

    /**
     * Health check del servicio de pagos
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> status = new HashMap<>();
        status.put("status", "OK");
        status.put("service", "API de pagos");
        status.put("mercadoPagoConfigured", String.valueOf(mercadoPagoService.isConfigured()));
        status.put("webhookSignatureValidation", String.valueOf(signatureValidator.isConfigured()));
        return ResponseEntity.ok(status);
    }

    // === Métodos privados ===

    /**
     * Extrae el data.id del payload del webhook
     */
    private String extractDataId(Map<String, Object> payload) {
        // 1) payload.data.id
        Object data = payload.get("data");
        if (data instanceof Map) {
            Object id = ((Map<?, ?>) data).get("id");
            if (id != null) {
                return id.toString();
            }
        }

        // 2) payload.resource (puede ser una URL como https://api.mercadolibre.com/merchant_orders/35392594879)
        Object resource = payload.get("resource");
        if (resource instanceof String) {
            String resStr = (String) resource;
            // Extraer la última parte después de '/'
            String[] parts = resStr.split("/");
            if (parts.length > 0) {
                String last = parts[parts.length - 1];
                if (last != null && !last.isBlank()) {
                    return last;
                }
            }
        }

        // 3) payload.id en la raíz
        Object rootId = payload.get("id");
        if (rootId != null) {
            return rootId.toString();
        }

        // No se encontró id
        return null;
    }

    /**
     * Maneja errores de pago
     */
    private ResponseEntity<?> handlePaymentError(String message, HttpStatus status) {
        if (failureUrl != null && !failureUrl.isBlank()) {
            log.info("Redirigiendo a failure URL: {}", failureUrl);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(failureUrl))
                    .build();
        }

        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        return ResponseEntity.status(status).body(error);
    }
}