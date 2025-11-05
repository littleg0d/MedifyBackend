package com.tuapp.medicamentos.controller;

import com.tuapp.medicamentos.dto.PreferenciaRequest;
import com.tuapp.medicamentos.dto.PreferenciaResponse;
import com.tuapp.medicamentos.service.MercadoPagoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/pagos")
public class PagoController {

    @Autowired
    private MercadoPagoService mercadoPagoService;

    @PostMapping("/crear-preferencia")
    public ResponseEntity<?> crearPreferencia(@RequestBody PreferenciaRequest request) {
        try {
            // Validaciones básicas
            if (request.getNombreComercial() == null || request.getNombreComercial().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "El nombre comercial es requerido"));
            }

            if (request.getPrecio() == null || request.getPrecio() <= 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "El precio debe ser mayor a 0"));
            }

            if (request.getRecetaId() == null || request.getRecetaId().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "El ID de receta es requerido"));
            }

            Map<String, Object> resultado = mercadoPagoService.crearPreferencia(request);

            PreferenciaResponse response = new PreferenciaResponse(
                    (String) resultado.get("paymentUrl"),
                    (String) resultado.get("preferenceId")
            );

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            System.err.println("Error al crear preferencia: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al crear la preferencia de pago: " + e.getMessage()));
        }
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(@RequestBody Map<String, Object> payload) {
        try {
            System.out.println("Webhook recibido de Mercado Pago: " + payload);

            String type = (String) payload.get("type");

            if ("payment".equals(type)) {
                Map<String, Object> data = (Map<String, Object>) payload.get("data");
                String paymentId = data.get("id").toString();

                // Verificar el estado del pago
                Map<String, Object> pagoInfo = mercadoPagoService.verificarPago(paymentId);
                String status = (String) pagoInfo.get("status");

                System.out.println("Estado del pago " + paymentId + ": " + status);

                // Aquí deberías actualizar tu base de datos (Firebase)
                // Por ejemplo, cambiar el estado del pedido de "pendiente" a "pagado"
                // Para esto necesitarías integrar Firebase Admin SDK

                if ("approved".equals(status)) {
                    System.out.println("Pago aprobado. Actualizar estado del pedido.");
                    // Actualizar estado en Firebase
                }
            }

            return ResponseEntity.ok("OK");

        } catch (Exception e) {
            System.err.println("Error procesando webhook: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error");
        }
    }

    @GetMapping("/verificar/{paymentId}")
    public ResponseEntity<?> verificarPago(@PathVariable String paymentId) {
        try {
            Map<String, Object> pagoInfo = mercadoPagoService.verificarPago(paymentId);
            return ResponseEntity.ok(pagoInfo);
        } catch (Exception e) {
            System.err.println("Error al verificar pago: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al verificar el pago"));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("API de pagos funcionando correctamente");
    }
}