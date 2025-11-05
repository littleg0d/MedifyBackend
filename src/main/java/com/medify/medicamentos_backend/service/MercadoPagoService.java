package com.tuapp.medicamentos.service;

import com.tuapp.medicamentos.dto.PreferenciaRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class MercadoPagoService {

    @Value("${mercadopago.access.token}")
    private String accessToken;

    @Value("${mercadopago.notification.url}")
    private String notificationUrl;

    @Value("${mercadopago.success.url}")
    private String successUrl;

    @Value("${mercadopago.failure.url}")
    private String failureUrl;

    @Value("${mercadopago.pending.url}")
    private String pendingUrl;

    private static final String MP_API_URL = "https://api.mercadopago.com/checkout/preferences";

    public Map<String, Object> crearPreferencia(PreferenciaRequest request) {
        try {
            RestTemplate restTemplate = new RestTemplate();

            // Headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(accessToken);

            // Body de la preferencia
            Map<String, Object> preference = new HashMap<>();

            // Items
            List<Map<String, Object>> items = new ArrayList<>();
            Map<String, Object> item = new HashMap<>();

            // Título del producto
            item.put("title", request.getNombreComercial());
            item.put("quantity", 1);
            item.put("unit_price", request.getPrecio());
            item.put("currency_id", "ARS");

            // Descripción (opcional)
            String descripcion = request.getDescripcion();
            if (descripcion == null || descripcion.isEmpty()) {
                descripcion = "Medicamento";
                if (request.getFarmaciaNombre() != null) {
                    descripcion += " - " + request.getFarmaciaNombre();
                }
            }
            item.put("description", descripcion);

            // Imagen del producto (opcional pero muy recomendado)
            if (request.getImagenUrl() != null && !request.getImagenUrl().isEmpty()) {
                item.put("picture_url", request.getImagenUrl());
            }

            // Categoría
            item.put("category_id", "health");

            items.add(item);
            preference.put("items", items);

            // Metadata para identificar el pedido
            Map<String, String> metadata = new HashMap<>();
            metadata.put("recetaId", request.getRecetaId());
            if (request.getFarmaciaId() != null) {
                metadata.put("farmaciaId", request.getFarmaciaId());
            }
            if (request.getFarmaciaNombre() != null) {
                metadata.put("farmaciaNombre", request.getFarmaciaNombre());
            }
            preference.put("metadata", metadata);

            // URLs de retorno
            Map<String, String> backUrls = new HashMap<>();
            backUrls.put("success", successUrl);
            backUrls.put("failure", failureUrl);
            backUrls.put("pending", pendingUrl);
            preference.put("back_urls", backUrls);
            preference.put("auto_return", "approved");

            // URL de notificación (webhook)
            preference.put("notification_url", notificationUrl);

            // Configuración adicional
            preference.put("statement_descriptor", "MEDICAMENTOS");
            preference.put("external_reference", request.getRecetaId());

            // Información del pagador (personalización)
            Map<String, Object> payer = new HashMap<>();
            payer.put("name", "Cliente");
            payer.put("surname", "Farmacia");
            preference.put("payer", payer);

            // Configuración de visualización
            preference.put("binary_mode", false);

            // Métodos de pago
            Map<String, Object> paymentMethods = new HashMap<>();
            paymentMethods.put("installments", 12); // Hasta 12 cuotas
            preference.put("payment_methods", paymentMethods);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(preference, headers);

            // Llamada a la API de Mercado Pago
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    MP_API_URL,
                    entity,
                    Map.class
            );

            if (response.getStatusCode() == HttpStatus.CREATED) {
                Map<String, Object> responseBody = response.getBody();

                Map<String, Object> result = new HashMap<>();
                result.put("paymentUrl", responseBody.get("init_point"));
                result.put("preferenceId", responseBody.get("id"));

                return result;
            } else {
                throw new RuntimeException("Error al crear preferencia en Mercado Pago");
            }

        } catch (Exception e) {
            throw new RuntimeException("Error al comunicarse con Mercado Pago: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> verificarPago(String paymentId) {
        try {
            RestTemplate restTemplate = new RestTemplate();

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);

            String url = "https://api.mercadopago.com/v1/payments/" + paymentId;
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    Map.class
            );

            return response.getBody();

        } catch (Exception e) {
            throw new RuntimeException("Error al verificar pago: " + e.getMessage(), e);
        }
    }
}