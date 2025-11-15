package com.medify.medicamentos_backend.controller;

import com.medify.medicamentos_backend.dto.CotizacionRequest;
import com.medify.medicamentos_backend.service.CotizacionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Controlador para gestionar las cotizaciones de las farmacias
 */
@RestController
@RequestMapping("/api")
public class CotizacionController {

    private static final Logger log = LoggerFactory.getLogger(CotizacionController.class);

    private final CotizacionService cotizacionService;

    public CotizacionController(CotizacionService cotizacionService) {
        this.cotizacionService = cotizacionService;
    }

    /**
     * Endpoint para que una farmacia responda a una receta
     * Crea la cotización y registra que la farmacia respondió
     *
     * @param request Datos de la cotización (recetaId, farmaciaId, estado, descripcion, precio)
     * @return Response con cotizacionId y mensaje de éxito
     */
    @PostMapping("/responder-receta")
    public ResponseEntity<?> responderReceta(@Valid @RequestBody CotizacionRequest request) {

        log.info("📥 Recibiendo respuesta de farmacia - Receta: {}, Farmacia: {}, Estado: {}",
                request.getRecetaId(), request.getFarmaciaId(), request.getEstado());

        try {
            // Validaciones según el estado
            if ("cotizado".equals(request.getEstado())) {
                if (request.getDescripcion() == null || request.getDescripcion().trim().isEmpty()) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "La descripción es obligatoria para cotizaciones"));
                }
                if (request.getPrecio() == null || request.getPrecio() <= 0) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "El precio debe ser mayor a 0"));
                }
            }

            // Crear la cotización y registrar la respuesta de la farmacia
            String cotizacionId = cotizacionService.responderReceta(request);

            log.info("✅ Cotización creada exitosamente: {}", cotizacionId);

            Map<String, Object> response = new HashMap<>();
            response.put("cotizacionId", cotizacionId);
            response.put("mensaje", "Respuesta registrada exitosamente");

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ Validación fallida: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));

        } catch (IllegalStateException e) {
            log.warn("⚠️ Estado inválido: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));

        } catch (Exception e) {
            log.error("❌ Error inesperado al responder receta: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno al procesar la respuesta"));
        }
    }

    /**
     * Health check del servicio de cotizaciones
     */
    @GetMapping("/cotizaciones/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "service", "API de cotizaciones"
        ));
    }
}