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
import com.medify.medicamentos_backend.dto.PreferenciaRequest;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
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
            log.info("MercadoPago configurado en modo: {}", isTestMode() ? "TEST" : "PRODUCCIÃ“N");
        } else {
            log.warn("MERCADOPAGO_ACCESS_TOKEN no definido. Las llamadas fallarÃ¡n.");
        }
    }

    public boolean isTestMode() {
        return accessToken != null && accessToken.startsWith("TEST-");
    }

    public boolean isConfigured() {
        return accessToken != null && !accessToken.isBlank();
    }

    /**
     * Crea una preferencia de pago con el pedidoId como external_reference
     */
    public Preference crearPreferencia(PreferenciaRequest request, String pedidoId)
            throws MPException, MPApiException {

        PreferenceItemRequest item = PreferenceItemRequest.builder()
                .id(request.getRecetaId())
                .title(request.getNombreComercial())
                .description(request.getDescripcion())
                .pictureUrl(request.getImagenUrl())
                .categoryId("health")
                .quantity(1)
                .currencyId("ARS")
                .unitPrice(new BigDecimal(request.getPrecio()))
                .build();

        PreferenceBackUrlsRequest backUrls = PreferenceBackUrlsRequest.builder()
                .success(successUrl)
                .failure(failureUrl)
                .pending(pendingUrl)
                .build();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("recetaId", request.getRecetaId());
        metadata.put("pedidoId", pedidoId);
        if (request.getFarmaciaId() != null) {
            metadata.put("farmaciaId", request.getFarmaciaId());
        }

        PreferenceRequest preferenceRequest = PreferenceRequest.builder()
                .items(List.of(item))
                .backUrls(backUrls)
                .notificationUrl(notificationUrl)
                .externalReference(pedidoId)
                .metadata(metadata)
                .build();

        logPreferenceRequest(preferenceRequest);

        try {
            PreferenceClient client = new PreferenceClient();
            return client.create(preferenceRequest);
        } catch (MPApiException mpEx) {
            logMPApiException(mpEx, pedidoId);
            throw mpEx;
        }
    }

    public Payment verificarPago(String paymentId) throws MPException, MPApiException {
        PaymentClient client = new PaymentClient();
        return client.get(Long.valueOf(paymentId));
    }

    // === MÃ©todos privados de logging ===

    private void logPreferenceRequest(PreferenceRequest request) {
        if (!log.isDebugEnabled()) {
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(request);
            log.debug("Creando preferencia MercadoPago: {}", json);
        } catch (JsonProcessingException e) {
            log.debug("No se pudo serializar preferenceRequest: {}", e.getMessage());
        }
    }

    private void logMPApiException(MPApiException mpEx, String pedidoId) {
        log.error("MPApiException creando preferencia (pedidoId={}): {}",
                pedidoId, mpEx.getMessage());

        // Intenta loggear detalles adicionales si estÃ¡n disponibles
        try {
            int statusCode = mpEx.getStatusCode();
            log.error("Status code: {}", statusCode);

            if (mpEx.getApiResponse() != null) {
                String content = mpEx.getApiResponse().getContent();
                log.error("Response body: {}", content);
            }
        } catch (Exception e) {
            log.debug("No se pudieron extraer detalles adicionales del error", e);
        }
    }
}