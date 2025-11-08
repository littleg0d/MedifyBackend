package com.medify.medicamentos_backend.service;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.payment.Payment;
import com.medify.medicamentos_backend.util.WebhookPayloadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Servicio encargado de procesar pagos y webhooks de MercadoPago
 */
@Service
public class PagoProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PagoProcessingService.class);

    private final MercadoPagoService mercadoPagoService;
    private final FirebaseService firebaseService;

    public PagoProcessingService(MercadoPagoService mercadoPagoService,
                                 FirebaseService firebaseService) {
        this.mercadoPagoService = mercadoPagoService;
        this.firebaseService = firebaseService;
    }

    /**
     * Procesa el webhook recibido de MercadoPago
     * @return true si se procesÃ³ correctamente, false si se ignorÃ³
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

        procesarPago(paymentId);
        return true;
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

            if (pedidoId != null && "approved".equalsIgnoreCase(status)) {
                firebaseService.marcarPedidoComoPagado(pedidoId, paymentId, status);
                log.info("Pedido {} marcado como pagado", pedidoId);
            } else if (pedidoId == null) {
                log.warn("Pago {} aprobado pero sin pedidoId asociado", paymentId);
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
     * Limpia un pedido de Firestore (usado cuando falla la creaciÃ³n de preferencia)
     */
    public void limpiarPedido(String pedidoId) {
        if (pedidoId == null) {
            return;
        }

        try {
            firebaseService.borrarPedido(pedidoId);
            log.info("Pedido {} eliminado tras error en creaciÃ³n de preferencia", pedidoId);
        } catch (Exception ex) {
            log.error("Error eliminando pedido {}: {}", pedidoId, ex.getMessage(), ex);
        }
    }

    /**
     * Obtiene el pedidoId desde el objeto Payment de MercadoPago
     */
    private String obtenerPedidoId(Payment payment) {
        // Primero intenta external_reference
        if (payment.getExternalReference() != null && !payment.getExternalReference().isBlank()) {
            return payment.getExternalReference();
        }

        // Fallback a metadata
        if (payment.getMetadata() != null && payment.getMetadata().get("pedidoId") != null) {
            return payment.getMetadata().get("pedidoId").toString();
        }

        return null;
    }
}