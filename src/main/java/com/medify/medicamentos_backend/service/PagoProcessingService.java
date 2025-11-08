package com.medify.medicamentos_backend.service;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.payment.Payment;
import com.medify.medicamentos_backend.util.WebhookPayloadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Servicio encargado de procesar pagos y webhooks de MercadoPago
 * con protección contra procesamiento duplicado y rate limiting
 */
@Service
public class PagoProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PagoProcessingService.class);

    private final MercadoPagoService mercadoPagoService;
    private final FirebaseService firebaseService;
    private final WebhookLockService webhookLockService;
    private final RateLimitService rateLimitService;

    public PagoProcessingService(MercadoPagoService mercadoPagoService,
                                 FirebaseService firebaseService,
                                 WebhookLockService webhookLockService,
                                 RateLimitService rateLimitService) {
        this.mercadoPagoService = mercadoPagoService;
        this.firebaseService = firebaseService;
        this.webhookLockService = webhookLockService;
        this.rateLimitService = rateLimitService;
    }

    /**
     * Procesa el webhook recibido de MercadoPago con protecciones
     * @return true si se procesó correctamente, false si se ignoró
     */
    public boolean procesarWebhook(Map<String, Object> payload) {
        String type = WebhookPayloadUtils.extractString(payload, "type");

        if (!"payment".equals(type)) {
            log.debug("Webhook ignorado, type={}", type);
            return false;
        }

        String paymentId = WebhookPayloadUtils.extractPaymentId(payload);
        if (paymentId == null) {
            log.warn("Webhook de payment sin ID: {}", payload);
            return false;
        }

        // 1. Verificar rate limit para este payment
        if (!rateLimitService.allowWebhook(paymentId)) {
            log.error("Rate limit excedido para payment {}, rechazando webhook", paymentId);
            return false;
        }

        // 2. Intentar adquirir lock para evitar procesamiento duplicado
        if (!webhookLockService.tryAcquireLock(paymentId)) {
            log.warn("No se pudo adquirir lock para payment {}, posible webhook duplicado", paymentId);
            return false;
        }

        try {
            // 3. Procesar el pago
            procesarPago(paymentId);
            return true;
        } finally {
            // 4. Siempre liberar el lock
            webhookLockService.releaseLock(paymentId);
        }
    }

    /**
     * Procesa un pago verificando su estado y actualizando Firestore
     */
    public void procesarPago(String paymentId) {
        try {
            Payment payment = mercadoPagoService.verificarPago(paymentId);
            String status = payment.getStatus() != null ? payment.getStatus().toString() : null;
            String pedidoId = obtenerPedidoId(payment);

            log.info("Pago {} - Status: {} - Pedido: {}", paymentId, status, pedidoId);

            if (pedidoId == null) {
                log.warn("Pago {} sin pedidoId asociado", paymentId);
                return;
            }

            // Mapear el status de MercadoPago a estado del pedido
            String estadoPedido = mapearEstadoPago(status);

            // Actualizar según el estado
            if ("approved".equalsIgnoreCase(status)) {
                // Usar método idempotente para pagos aprobados
                boolean updated = firebaseService.marcarPedidoComoPagadoIdempotente(
                        pedidoId, paymentId, status
                );

                if (updated) {
                    log.info("✓ Pedido {} marcado como pagado exitosamente", pedidoId);
                } else {
                    log.info("Pedido {} ya estaba pagado, operación idempotente", pedidoId);
                }
            } else {
                // Para otros estados, actualizar directamente
                Map<String, Object> updates = new HashMap<>();
                updates.put("estado", estadoPedido);
                updates.put("paymentId", paymentId);
                updates.put("paymentStatus", status);

                firebaseService.actualizarPedido(pedidoId, updates);

                log.info("Pedido {} actualizado a estado: {} (status MP: {})",
                        pedidoId, estadoPedido, status);
            }

        } catch (MPException | MPApiException e) {
            log.error("Error de MercadoPago procesando pago {}: {}", paymentId, e.getMessage());
            throw new RuntimeException("Error verificando pago con MercadoPago", e);
        } catch (Exception e) {
            log.error("Error procesando pago {}: {}", paymentId, e.getMessage(), e);
            throw new RuntimeException("Error procesando pago", e);
        }
    }

    /**
     * Mapea el status de MercadoPago a un estado interno del pedido
     */
    private String mapearEstadoPago(String mpStatus) {
        if (mpStatus == null) {
            return "desconocido";
        }

        switch (mpStatus.toLowerCase()) {
            case "approved":
                return "pagado";
            case "rejected":
                return "rechazado";
            case "cancelled":
                return "cancelado";
            case "pending":
            case "in_process":
            case "in_mediation":
                return "pendiente";
            default:
                log.warn("Status de MercadoPago no reconocido: {}", mpStatus);
                return "desconocido";
        }
    }

    /**
     * Limpia un pedido de Firestore (usado cuando falla la creación de preferencia)
     */
    public void limpiarPedido(String pedidoId) {
        if (pedidoId == null) {
            return;
        }

        try {
            firebaseService.borrarPedido(pedidoId);
            log.info("Pedido {} eliminado tras error en creación de preferencia", pedidoId);
        } catch (Exception ex) {
            log.error("Error eliminando pedido {}: {}", pedidoId, ex.getMessage(), ex);
        }
    }

    /**
     * Obtiene el pedidoId desde el objeto Payment de MercadoPago
     */
    private String obtenerPedidoId(Payment payment) {
        if (payment.getExternalReference() != null && !payment.getExternalReference().isBlank()) {
            return payment.getExternalReference();
        }

        if (payment.getMetadata() != null && payment.getMetadata().get("pedidoId") != null) {
            return payment.getMetadata().get("pedidoId").toString();
        }

        return null;
    }
}