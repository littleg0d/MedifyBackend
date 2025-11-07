package com.medify.medicamentos_backend.service;

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

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @PostConstruct
    public void init() {
        // Configura el Access Token una sola vez al iniciar la aplicación si está presente
        if (accessToken != null && !accessToken.isBlank()) {
            MercadoPagoConfig.setAccessToken(accessToken);
        } else {
            // No hacemos set deltoken (en entornos de test/local puede estar ausente)
            log.warn("[WARN] MERCADOPAGO_ACCESS_TOKEN no definido. Las llamadas a Mercado Pago fallarán si se intentan ejecutar.");
        }
    }

    // Indicador de modo de pruebas (tokens de prueba de Mercado Pago suelen empezar con "TEST-")
    public boolean isTestMode() {
        return accessToken != null && accessToken.startsWith("TEST-");
    }

    // Indica si el servicio tiene token configurado (útil para evitar llamadas cuando está vacío)
    public boolean isConfigured() {
        return accessToken != null && !accessToken.isBlank();
    }

    public Preference crearPreferencia(PreferenciaRequest request) throws MPException, MPApiException {
        // Mantener por compatibilidad: usa recetaId como externalReference si no se proporciona otro.
        return crearPreferencia(request, request.getRecetaId());
    }

    /**
     * Crea una preferencia usando pedidoId como external_reference
     */
    public Preference crearPreferencia(PreferenciaRequest request, String pedidoId) throws MPException, MPApiException {

        // 1. Crea el item
        PreferenceItemRequest itemRequest = PreferenceItemRequest.builder()
                .id(request.getRecetaId())
                .title(request.getNombreComercial())
                .description(request.getDescripcion())
                .pictureUrl(request.getImagenUrl())
                .categoryId("health")
                .quantity(1)
                .currencyId("ARS")
                .unitPrice(new BigDecimal(request.getPrecio())) // Usa BigDecimal para dinero
                .build();

        List<PreferenceItemRequest> items = new ArrayList<>();
        items.add(itemRequest);

        // 2. Crea las URLs de retorno
        PreferenceBackUrlsRequest backUrls = PreferenceBackUrlsRequest.builder()
                .success(successUrl)
                .failure(failureUrl)
                .pending(pendingUrl)
                .build();

        // 3. Metadata (para rastrear el pedido)
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("recetaId", request.getRecetaId());
        metadata.put("pedidoId", pedidoId);
        if (request.getFarmaciaId() != null) {
            metadata.put("farmaciaId", request.getFarmaciaId());
        }

        // 4. Crea la Preferencia completa
        PreferenceRequest preferenceRequest = PreferenceRequest.builder()
                .items(items)
                .backUrls(backUrls)
                // COMENTADO: autoReturn requiere que success URL esté definida y sea válida
                // Si no tienes configurada mercadopago.success.url, comenta esta línea
                // .autoReturn("approved")
                .notificationUrl(notificationUrl)
                .externalReference(pedidoId) // ID del pedido en Firestore
                .metadata(metadata)
                .build();

        // 5. Crea el cliente y envía la petición con logging detallado en caso de error
        PreferenceClient client = createPreferenceClient();
        try {
            // Log the outgoing payload at DEBUG level (safe: does not include secret token)
            try {
                if (log.isDebugEnabled()) {
                    String prJson = objectMapper.writeValueAsString(preferenceRequest);
                    log.debug("MercadoPago preference request payload: {}", prJson);
                }
            } catch (Exception e) {
                log.debug("No se pudo serializar preferenceRequest para logging: {}", e.getMessage());
            }

            return client.create(preferenceRequest);
        } catch (MPApiException mpEx) {
            // Log detailed info about the API error if available
            log.error("MercadoPago MPApiException creando preferencia (pedidoId={}): {}", pedidoId, mpEx.getMessage());

            // Intentar extraer información adicional del objeto de la excepción (apiResponse) vía reflexión
            try {
                Object apiResponse = null;
                try {
                    java.lang.reflect.Method m = mpEx.getClass().getMethod("getApiResponse");
                    apiResponse = m.invoke(mpEx);
                } catch (NoSuchMethodException nsme) {
                    // intentar campo 'apiResponse'
                    try {
                        java.lang.reflect.Field f = mpEx.getClass().getDeclaredField("apiResponse");
                        f.setAccessible(true);
                        apiResponse = f.get(mpEx);
                    } catch (NoSuchFieldException | IllegalAccessException ignore) {
                        // nothing
                    }
                }

                if (apiResponse != null) {
                    try {
                        // Try to extract status code and body via common method names
                        String statusStr = null;
                        Object bodyObj = null;
                        try {
                            // common method names
                            for (String methodName : new String[]{"getStatusCode","getStatus","getCode","statusCode"}) {
                                try {
                                    java.lang.reflect.Method ms = apiResponse.getClass().getMethod(methodName);
                                    Object st = ms.invoke(apiResponse);
                                    if (st != null) { statusStr = st.toString(); break; }
                                } catch (NoSuchMethodException ignored) { }
                            }

                            for (String bodyMethod : new String[]{"getJsonElement","getBody","getResponse","getContent","getText","getData"}) {
                                try {
                                    java.lang.reflect.Method mb = apiResponse.getClass().getMethod(bodyMethod);
                                    Object bo = mb.invoke(apiResponse);
                                    if (bo != null) { bodyObj = bo; break; }
                                } catch (NoSuchMethodException ignored) { }
                            }
                        } catch (Exception reflEx) {
                            log.debug("No se pudo extraer status/body vía reflexión: {}", reflEx.getMessage());
                        }

                        String bodyStr = null;
                        if (bodyObj != null) {
                            try { bodyStr = objectMapper.writeValueAsString(bodyObj); } catch (Exception e) { bodyStr = bodyObj.toString(); }
                        }

                        log.error("Detalle apiResponse MercadoPago - status: {} body: {}", statusStr, bodyStr);
                    } catch (Exception serEx) {
                        log.error("Detalle apiResponse (toString): {}", apiResponse.toString());
                    }
                } else {
                    log.error("No se pudo extraer apiResponse del MPApiException");
                }
            } catch (Exception ex) {
                log.error("Error intentando extraer detalles de MPApiException: {}", ex.getMessage());
            }

            throw mpEx; // rethrow so controller handles cleanup and response
        }
    }

    public Payment verificarPago(String paymentId) throws MPException, MPApiException {
        // Verifica el pago usando el ID numérico
        PaymentClient client = new PaymentClient();
        return client.get(Long.valueOf(paymentId));
    }

    private boolean isValidHttpUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
    // Allow tests to override or mock the client creation
    protected PreferenceClient createPreferenceClient() {
        return new PreferenceClient();
    }
}