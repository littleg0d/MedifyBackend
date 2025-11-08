package com.medify.medicamentos_backend.controller;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.preference.Preference;
import com.medify.medicamentos_backend.dto.PreferenciaRequest;
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
 * Controlador REST para la gestiÃ³n de pagos con MercadoPago
 */
@RestController
@RequestMapping("/api/pagos")
public class PagoController {

    private static final Logger log = LoggerFactory.getLogger(PagoController.class);

    private final MercadoPagoService mercadoPagoService;
    private final FirebaseService firebaseService;
    private final PagoProcessingService pagoProcessingService;

    @Value("${mercadopago.failure.url:}")
    private String failureUrl;

    public PagoController(MercadoPagoService mercadoPagoService,
                          FirebaseService firebaseService,
                          PagoProcessingService pagoProcessingService) {
        this.mercadoPagoService = mercadoPagoService;
        this.firebaseService = firebaseService;
        this.pagoProcessingService = pagoProcessingService;
    }

    /**
     * Crea una preferencia de pago en MercadoPago
     * 1. Valida configuraciÃ³n
     * 2. Crea pedido en Firestore
     * 3. Crea preferencia en MercadoPago
     */
    @PostMapping("/crear-preferencia")
    public ResponseEntity<?> crearPreferencia(@Valid @RequestBody PreferenciaRequest request) {

        log.info("Creando pedido en Firestore para receta: {}", request.getRecetaId());

        String pedidoId = null;
        try {
            // 1. Validar configuraciÃ³n ANTES de crear pedido
            if (!mercadoPagoService.isConfigured()) {
                log.warn("Mercado Pago no configurado");
                return handlePaymentError("Proveedor de pagos no configurado", HttpStatus.SERVICE_UNAVAILABLE);
            }

            // 2. Crear pedido en Firestore
            pedidoId = firebaseService.crearPedido(request);
            log.info("Pedido creado con id: {}", pedidoId);

            // 3. Crear preferencia en Mercado Pago
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
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(@RequestBody Map<String, Object> payload) {

        log.info("Webhook recibido: {}", payload);

        try {
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
                    .body(Map.of("error", "Payment ID invÃ¡lido"));
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
        return ResponseEntity.ok(status);
    }

    // === MÃ©todos privados ===

    /**
     * Maneja errores de pago devolviendo JSON o redirigiendo segÃºn configuraciÃ³n
     */
    private ResponseEntity<?> handlePaymentError(String message, HttpStatus status) {
        // Si hay URL de fallo configurada, redirigir
        if (failureUrl != null && !failureUrl.isBlank()) {
            log.info("Redirigiendo a failure URL: {}", failureUrl);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(failureUrl))
                    .build();
        }

        // Si no, devolver error JSON
        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        return ResponseEntity.status(status).body(error);
    }
}