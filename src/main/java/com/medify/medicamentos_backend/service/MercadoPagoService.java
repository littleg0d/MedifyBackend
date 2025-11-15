package com.medify.medicamentos_backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.client.preference.*;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.preference.Preference;
import com.medify.medicamentos_backend.dto.PedidoData;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MercadoPagoService {

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoService.class);

    private final ObjectMapper objectMapper;

    @Value("${mercadopago.access.token:}")
    private String accessToken;

    @Value("${mercadopago.notification.url}")
    private String notificationUrl;

    @Value("${mercadopago.success.url}")
    private String successUrl;

    @Value("${mercadopago.failure.url}")
    private String failureUrl;

    @Value("${mercadopago.pending.url}")
    private String pendingUrl;

    public MercadoPagoService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (accessToken != null && !accessToken.isBlank()) {
            MercadoPagoConfig.setAccessToken(accessToken);
            log.info("✅ MercadoPago configurado");
        } else {
            log.warn("⚠️ MERCADOPAGO_ACCESS_TOKEN no definido.");
        }
    }

    public boolean isConfigured() {
        return accessToken != null && !accessToken.isBlank();
    }

    /**
     * Crea una preferencia de pago usando los datos completos obtenidos de Firebase
     * @param datos Datos completos del pedido (usuario, farmacia, cotización)
     * @param pedidoId ID del pedido ya creado en Firestore
     * @return Preferencia creada en MercadoPago
     */
    public Preference crearPreferencia(PedidoData datos, String pedidoId)
            throws MPException, MPApiException {

        // Construir el item con los datos de la cotización
        PreferenceItemRequest item = PreferenceItemRequest.builder()
                .id(datos.getRecetaId())
                .title(datos.getNombreComercial()) // Nombre de la farmacia
                .description(datos.getDescripcion()) // Descripción de la cotización
                .pictureUrl(datos.getImagenUrl()) // Imagen de la receta
                .categoryId("health")
                .quantity(1)
                .currencyId("ARS")
                .unitPrice(new BigDecimal(datos.getPrecio())) // Precio de la cotización
                .build();

        // URLs de retorno
        PreferenceBackUrlsRequest backUrls = PreferenceBackUrlsRequest.builder()
                .success(successUrl)
                .failure(failureUrl)
                .pending(pendingUrl)
                .build();

        // Metadata con todos los IDs relevantes
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("recetaId", datos.getRecetaId());
        metadata.put("pedidoId", pedidoId);
        metadata.put("farmaciaId", datos.getFarmaciaId());
        metadata.put("cotizacionId", datos.getCotizacionId());
        metadata.put("userId", datos.getUserId());

        // Establecer expiración 10 minutos a partir de ahora (UTC)
        OffsetDateTime expiration = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10);

        // Construir la preferencia de forma simple
        PreferenceRequest preferenceRequest = PreferenceRequest.builder()
                .items(List.of(item))
                .backUrls(backUrls)
                .notificationUrl(notificationUrl)
                .externalReference(pedidoId)
                .metadata(metadata)
                .expirationDateTo(expiration)
                .statementDescriptor("MEDIFY - " + datos.getNombreComercial())
                .build();

        logPreferenceRequest(preferenceRequest);

        try {
            PreferenceClient client = new PreferenceClient();
            Preference preferencia = client.create(preferenceRequest);

            log.info("✅ Preferencia creada exitosamente");
            log.info("   Precio: ${}", datos.getPrecio());
            log.info("   Usuario: {}", datos.getUserName());
            log.info("   Farmacia: {}", datos.getNombreComercial());

            return preferencia;

        } catch (MPApiException mpEx) {
            logMPApiException(mpEx, pedidoId);
            throw mpEx;
        }
    }

    /**
     * Verifica el estado de un pago en MercadoPago
     */
    public Payment verificarPago(String paymentId) throws MPException, MPApiException {
        PaymentClient client = new PaymentClient();
        return client.get(Long.valueOf(paymentId));
    }

    // === Métodos privados de logging ===

    private void logPreferenceRequest(PreferenceRequest request) {
        if (!log.isDebugEnabled()) {
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(request);
            log.debug("📄 Creando preferencia MercadoPago: {}", json);
        } catch (JsonProcessingException e) {
            log.debug("⚠️ No se pudo serializar preferenceRequest: {}", e.getMessage());
        }
    }

    private void logMPApiException(MPApiException mpEx, String pedidoId) {
        log.error("❌ MPApiException creando preferencia (pedidoId={}): {}",
                pedidoId, mpEx.getMessage());

        try {
            int statusCode = mpEx.getStatusCode();
            log.error("   Status code: {}", statusCode);

            if (mpEx.getApiResponse() != null) {
                String content = mpEx.getApiResponse().getContent();
                log.error("   Response body: {}", content);
            }
        } catch (Exception e) {
            log.debug("⚠️ No se pudieron extraer detalles adicionales del error", e);
        }
    }
}