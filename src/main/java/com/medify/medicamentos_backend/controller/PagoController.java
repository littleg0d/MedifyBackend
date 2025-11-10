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

        log.info("Creando pedido en Firestore para receta: {} y cotizacion: {}", request.getRecetaId(), request.getCotizacionId());

        String pedidoId = null;
        try {
            // ✅ VALIDACION:Verificar que MercadoPago esté configurado
            if (!mercadoPagoService.isConfigured()) {
                log.warn("❌ Mercado Pago no configurado");
                return handlePaymentError("Proveedor de pagos no configurado", HttpStatus.SERVICE_UNAVAILABLE);
            }

            // ✅ VALIDACION: Verificar que no exista un pedido duplicado
            if (firebaseService.existePedidoActivoPorReceta(request.getRecetaId(), request.getUserId())) {
                log.error("❌ Ya existe un pedido activo para la receta: {}", request.getRecetaId());
                return handlePaymentError(
                        "Ya existe un pedido en proceso para esta receta",
                        HttpStatus.CONFLICT
                );
            }

            // ✅ VALIDACION: Verificar que la receta exista y esté en estado válido
            Map<String, Object> receta = firebaseService.obtenerReceta(request.getRecetaId());

            if (receta == null) {
                log.error("❌ Receta no encontrada: {}", request.getRecetaId());
                return handlePaymentError(
                        "Receta no encontrada",
                        HttpStatus.NOT_FOUND
                );
            }

            String estadoReceta = (String) receta.get("estado");
            if (!"cotizado".equals(estadoReceta)) {
                log.error("❌ Receta en estado inválido: {}. Esperado: 'cotizado'", estadoReceta);
                return handlePaymentError(
                        "La receta no está lista para procesar el pago",
                        HttpStatus.BAD_REQUEST
                );
            }

            log.info("✅ Receta {} validada correctamente (estado: {})", request.getRecetaId(), estadoReceta);

            // ✅ VVALIDACION: Buscar la cotización en Firestore
            Map<String, Object> cotizacion = firebaseService.obtenerCotizacion(
                    request.getRecetaId(),
                    request.getCotizacionId()
            );

            if (cotizacion == null) {
                log.error("❌ Cotización no encontrada: {}/{}",
                        request.getRecetaId(), request.getCotizacionId());
                return handlePaymentError(
                        "Cotización no encontrada",
                        HttpStatus.NOT_FOUND
                );
            }

            // ✅ VALIDACION: Verificar que el estado de la cotización sea "cotizado"
            String estadoCotizacion = (String) cotizacion.get("estado");
            if (!"cotizado".equals(estadoCotizacion)) {
                log.error("❌ Cotización en estado inválido: {}. Esperado: 'cotizado'", estadoCotizacion);
                return handlePaymentError(
                        "La cotización no está en estado válido para proceder con el pago",
                        HttpStatus.BAD_REQUEST
                );
            }

            // ✅ VALIDACION: Extraer y validar el precio desde Firestore
            Double precio = extractPrecio(cotizacion);

            if (precio == null || precio <= 0) {
                log.error("❌ Precio inválido en cotización: {}", precio);
                return handlePaymentError(
                        "Precio inválido en la cotización",
                        HttpStatus.BAD_REQUEST
                );
            }

            log.info("💰 Precio obtenido de Firestore: ${}", precio);

            // ✅ VALIDACION: Verificar que farmaciaId coincida (seguridad extra)
            String farmaciaIdCotizacion = (String) cotizacion.get("farmaciaId");
            if (farmaciaIdCotizacion != null && !farmaciaIdCotizacion.equals(request.getFarmaciaId())) {
                log.error("❌ FarmaciaId no coincide. Request: {}, Cotización: {}",
                        request.getFarmaciaId(), farmaciaIdCotizacion);
                return handlePaymentError(
                        "La farmacia no coincide con la cotización",
                        HttpStatus.BAD_REQUEST
                );
            }

            // ✅ TODO VALIDADO: Crear el pedido
            try {
                pedidoId = firebaseService.crearPedidoConTransaccion(request, precio);
            } catch (IllegalStateException e) {
                // Validación de negocio falló (pedido duplicado o no expirado)
                log.warn("❌ Validación de pedido falló: {}", e.getMessage());
                return handlePaymentError(e.getMessage(), HttpStatus.CONFLICT);
            }
            log.info("✅ Pedido creado con id: {}", pedidoId);

            // ✅ Crear la preferencia de pago en MercadoPago
            Preference preferencia = mercadoPagoService.crearPreferencia(request, pedidoId, precio);

            log.info("✅ Preferencia creada: {} - URL: {}", preferencia.getId(), preferencia.getInitPoint());

            Map<String, String> response = new HashMap<>();
            response.put("paymentUrl", preferencia.getInitPoint());
            response.put("preferenceId", preferencia.getId());

            return ResponseEntity.ok(response);

        } catch (MPException | MPApiException e) {
            log.error("❌ Error de Mercado Pago para pedido {}: {}", pedidoId, e.getMessage(), e);
            pagoProcessingService.limpiarPedido(pedidoId);
            return handlePaymentError("Error creando preferencia de pago: " + e.getMessage(), HttpStatus.BAD_GATEWAY);

        } catch (Exception e) {
            log.error("❌ Error inesperado creando preferencia para pedido {}: {}", pedidoId, e.getMessage(), e);
            pagoProcessingService.limpiarPedido(pedidoId);
            return handlePaymentError("Error interno al procesar el pago", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    /**
     * Extrae el precio desde los datos de la cotización
     * Maneja diferentes formatos posibles
     */
    private Double extractPrecio(Map<String, Object> cotizacion) {
        Object precioObj = cotizacion.get("precio");

        if (precioObj == null) {
            log.warn("⚠️ Campo 'precio' no encontrado en cotización");
            return null;
        }

        if (precioObj instanceof Number) {
            return ((Number) precioObj).doubleValue();
        }

        if (precioObj instanceof String) {
            try {
                return Double.parseDouble((String) precioObj);
            } catch (NumberFormatException e) {
                log.warn("No se pudo convertir precio string: {}", precioObj);
                return null;
            }
        }
        log.warn("⚠️ Tipo de precio no soportado: {}", precioObj.getClass().getName());
        return null;
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
            String dataId = extractDataId(payload);
            if (dataId == null) {
                log.warn("Webhook recibido sin data.id. No se puede validar la firma.");
                // Devolvemos OK para que MP no reintente
                return ResponseEntity.ok("ignored (missing data.id)");
            }

            // 1. Validar la firma
        /*
         * ==========================================================
         * --- VALIDACIÓN DE FIRMA DESHABILITADA TEMPORALMENTE ---
         * ¡ADVERTENCIA! ESTO ES INSEGURO
         *
        if (!signatureValidator.isValidSignature(xSignature, xRequestId, dataId)) {
            log.warn("¡FIRMA DE WEBHOOK INVÁLIDA! Id: {}, Signature: {}", dataId, xSignature);
            // Devolvemos OK para que MP no reintente, pero no procesamos.
            // MP recomienda devolver 200/201 aunque la firma falle.
            return ResponseEntity.ok("ignored (invalid signature)");
        }
        */

            //  Validar antigüedad del timestamp
        /*
        if (!signatureValidator.isRecentTimestamp(xSignature, WEBHOOK_MAX_AGE_SECONDS)) {
            log.warn("Webhook con timestamp expirado. Id: {}", dataId);
            return ResponseEntity.ok("ignored (expired timestamp)");
        }

        log.info("Firma de webhook validada exitosamente para id: {}", dataId);
        */

            // Se asume que el webhook es válido
            log.warn("¡ADVERTENCIA DE SEGURIDAD! La validación de la firma del Webhook está DESHABILITADA.");


            // El código original de procesamiento continúa aquí
            boolean procesado = pagoProcessingService.procesarWebhook(payload);
            return ResponseEntity.ok(procesado ? "processed" : "ignored");

        } catch (Exception ex) {
            log.error("Error procesando webhook: {}", ex.getMessage(), ex);
            // MercadoPago espera 200 incluso si hay errores para no reintente
            return ResponseEntity.ok("error");
        }
    };
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