package com.medify.medicamentos_backend.controller;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.preference.Preference;
import com.medify.medicamentos_backend.dto.PreferenciaRequest;
import com.medify.medicamentos_backend.service.FirebaseService;
import com.medify.medicamentos_backend.service.MercadoPagoService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/pagos")
public class PagoController {
    private static final Logger log = LoggerFactory.getLogger(PagoController.class);

    @Autowired
    private MercadoPagoService mercadoPagoService;

    @Autowired
    private FirebaseService firebaseService;

    // Inyectamos la URL de fallo para ser usada en caso de error y redirección
    @Value("${mercadopago.failure.url:}")
    private String failureUrl;

    @PostMapping("/crear-preferencia")
    public ResponseEntity<Map<String, String>> crearPreferencia(
            @Valid @RequestBody PreferenciaRequest request) throws Exception {

        log.info("Creando pedido en Firestore para receta: {}", request.getRecetaId());

        // 1) Crear pedido en Firestore (estado 'pendiente', fechacreacion set en serverTimestamp)
        String pedidoId = firebaseService.crearPedido(request);

        log.info("Pedido creado en Firestore con id: {}. Creando preferencia en Mercado Pago...", pedidoId);

        try {

            if (!mercadoPagoService.isConfigured()) {
                log.warn("Mercado Pago no está configurado (token ausente). Eliminando pedido {} y devolviendo error/redirect.", pedidoId);
                try {
                    firebaseService.borrarPedido(pedidoId);
                } catch (Exception ex) {
                    log.error("Error borrando pedido {} cuando Mercado Pago no está configurado: {}", pedidoId, ex.getMessage(), ex);
                }

                if (failureUrl != null && !failureUrl.isBlank()) {
                    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(failureUrl)).build();
                }

                Map<String, String> err = new HashMap<>();
                err.put("error", "Proveedor de pagos no configurado");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(err);
            }

            // 2) Crear preferencia en Mercado Pago usando pedidoId como external_reference
            Preference resultado = mercadoPagoService.crearPreferencia(request, pedidoId);

            String paymentUrl = resultado.getInitPoint();

            log.info("Preferencia creada con ID: {} y paymentUrl: {}", resultado.getId(), paymentUrl);

            Map<String, String> resp = new HashMap<>();
            resp.put("paymentUrl", paymentUrl);

            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("Error creando preferencia en Mercado Pago para pedido {}: {}", pedidoId, e.getMessage(), e);
            try {
                // Borrar el pedido si la creación de la preferencia falla
                firebaseService.borrarPedido(pedidoId);
                log.info("Pedido {} eliminado por error en creación de preferencia", pedidoId);
            } catch (Exception ex) {
                log.error("Error al eliminar pedido {} tras fallo en creación de preferencia: {}", pedidoId, ex.getMessage(), ex);
            }

            // Redirigir al cliente a la URL de fallo (si está configurada)
            if (failureUrl != null && !failureUrl.isBlank()) {
                log.info("Redirigiendo a URL de fallo: {}", failureUrl);
                return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(failureUrl)).build();
            }

            Map<String, String> err = new HashMap<>();
            err.put("error", "No se pudo crear la preferencia de pago");
            err.put("detail", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(@RequestBody Map<String, Object> payload) {

        log.info("Webhook recibido de Mercado Pago: {}", payload);

        try {
            Object typeObj = payload.get("type");
            String type = typeObj != null ? typeObj.toString() : null;

            if ("payment".equals(type)) {
                // Extraer paymentId de forma segura
                String paymentId = null;
                Object dataObj = payload.get("data");
                if (dataObj instanceof Map) {
                    Map<?, ?> dataMap = (Map<?, ?>) dataObj;
                    Object idObj = dataMap.get("id");
                    if (idObj != null) {
                        paymentId = idObj.toString();
                      }
                }

                if (paymentId == null) {
                    log.warn("Webhook 'payment' recibido pero no se encontró payment id en payload: {}", payload);
                    return ResponseEntity.ok("ignored");
                }

                Payment pagoInfo = mercadoPagoService.verificarPago(paymentId);
                String status = pagoInfo.getStatus() != null ? pagoInfo.getStatus().toString() : null;

                log.info("Estado del pago {}: {}", paymentId, status);

                // Obtener pedidoId desde external_reference o metadata de forma segura
                String pedidoId = null;
                if (pagoInfo.getExternalReference() != null) {
                    pedidoId = pagoInfo.getExternalReference();
                } else if (pagoInfo.getMetadata() != null && pagoInfo.getMetadata().get("pedidoId") != null) {
                    pedidoId = pagoInfo.getMetadata().get("pedidoId").toString();
                }

                if (pedidoId != null && "approved".equalsIgnoreCase(status)) {
                    try {
                        firebaseService.marcarPedidoComoPagado(pedidoId, paymentId, status);
                    } catch (Exception e) {
                        log.error("Error al marcar pedido {} como pagado: {}", pedidoId, e.getMessage(), e);
                    }
                } else {
                    log.info("No se procesó el pago en Firestore (pedidoId={} status={})", pedidoId, status);
                }
            }
        } catch (Exception ex) {
            // No lanzar excepciones al webhook para no provocar reintentos automáticos indeseados
            log.error("Error procesando webhook de Mercado Pago: {}", ex.getMessage(), ex);
        }

        return ResponseEntity.ok("OK");
    }

    @GetMapping("/verificar/{paymentId}")
    public ResponseEntity<Payment> verificarPago(@PathVariable String paymentId) throws MPException, MPApiException {
        log.info("Verificando pago manualmente: {}", paymentId);
        Payment pagoInfo = mercadoPagoService.verificarPago(paymentId);
        return ResponseEntity.ok(pagoInfo);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {

        return ResponseEntity.ok("API de pagos funcionando correctamente");
    }
}