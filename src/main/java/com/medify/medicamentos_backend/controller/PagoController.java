package com.medify.medicamentos_backend.controller;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.preference.Preference;
import com.medify.medicamentos_backend.dto.PedidoData;
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
 * Controlador REST para la gestión de pagos con MercadoPago
 * Ahora obtiene todos los datos necesarios desde Firebase
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
     * Obtiene todos los datos necesarios desde Firebase usando solo los IDs
     */
    @PostMapping("/crear-preferencia")
    public ResponseEntity<?> crearPreferencia(@Valid @RequestBody PreferenciaRequest request) {

        log.info("📝 Creando preferencia - Receta: {}, Cotización: {}, Farmacia: {}, Usuario: {}",
                request.getRecetaId(), request.getCotizacionId(),
                request.getFarmaciaId(), request.getUserId());

        String pedidoId = null;
        try {
            // ✅ VALIDACION: Verificar que MercadoPago esté configurado
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

            // 🔍 OBTENER TODOS LOS DATOS DESDE FIREBASE
            log.info("🔍 Obteniendo datos completos desde Firebase...");
            PedidoData datosCompletos;

            try {
                datosCompletos = firebaseService.obtenerDatosCompletosParaPedido(
                        request.getUserId(),
                        request.getFarmaciaId(),
                        request.getRecetaId(),
                        request.getCotizacionId()
                );
            } catch (IllegalArgumentException e) {
                // Error de validación de datos
                log.error("❌ Error de validación: {}", e.getMessage());
                return handlePaymentError(e.getMessage(), HttpStatus.BAD_REQUEST);
            }

            log.info("✅ Datos obtenidos correctamente:");
            log.info("   Usuario: {} ({})", datosCompletos.getUserName(), datosCompletos.getUserEmail());
            log.info("   Farmacia: {} ({})", datosCompletos.getNombreComercial(), datosCompletos.getFarmEmail());
            log.info("   Precio: ${}", datosCompletos.getPrecio());

            // ✅ TODO VALIDADO: Crear el pedido con los datos completos
            try {
                pedidoId = firebaseService.crearPedidoConTransaccion(datosCompletos);
            } catch (IllegalStateException e) {
                // Validación de negocio falló (pedido duplicado o no expirado)
                log.warn("❌ Validación de pedido falló: {}", e.getMessage());
                return handlePaymentError(e.getMessage(), HttpStatus.CONFLICT);
            }

            log.info("✅ Pedido creado con id: {}", pedidoId);

            // ✅ Crear la preferencia de pago en MercadoPago
            Preference preferencia = mercadoPagoService.crearPreferencia(datosCompletos, pedidoId);

            log.info("✅ Preferencia creada: {} - URL: {}", preferencia.getId(), preferencia.getInitPoint());

            Map<String, String> response = new HashMap<>();
            response.put("paymentUrl", preferencia.getInitPoint());
            response.put("preferenceId", preferencia.getId());
            response.put("pedidoId", pedidoId);

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
     * Recibe notificaciones de MercadoPago sobre cambios en pagos
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "x-signature", required = false) String xSignature,
            @RequestHeader(value = "x-request-id", required = false) String xRequestId) {

        log.info("📨 Webhook recibido: {}", payload);
        log.debug("Headers - x-signature: {}, x-request-id: {}", xSignature, xRequestId);

        try {
            boolean procesado = pagoProcessingService.procesarWebhook(payload);
            return ResponseEntity.ok(procesado ? "processed" : "ignored");

        } catch (Exception ex) {
            log.error("❌ Error procesando webhook: {}", ex.getMessage(), ex);
            // MercadoPago espera 200 incluso si hay errores para no reintente
            return ResponseEntity.ok("error");
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

    // === Métodos privados ===

    /**
     * Maneja errores de pago
     */
    private ResponseEntity<?> handlePaymentError(String message, HttpStatus status) {
        if (failureUrl != null && !failureUrl.isBlank()) {
            log.info("🔀 Redirigiendo a failure URL: {}", failureUrl);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(failureUrl))
                    .build();
        }

        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        return ResponseEntity.status(status).body(error);
    }
}