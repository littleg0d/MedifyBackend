package com.medify.medicamentos_backend.controller;

import com.dropbox.core.DbxException;
import com.medify.medicamentos_backend.service.RecetaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Controller para operaciones de recetas
 * Endpoint simplificado: recibe userId, addressId y file
 */
@RestController
@RequestMapping("/api/recetas")
public class RecetaController {

    private static final Logger log = LoggerFactory.getLogger(RecetaController.class);
    private final RecetaService recetaService;

    public RecetaController(RecetaService recetaService) {
        this.recetaService = recetaService;
    }

    /**
     * ⭐ ENDPOINT ATÓMICO CON MÚLTIPLES DIRECCIONES:
     * Recibe userId, addressId y file
     * Obtiene los datos del usuario y la dirección específica desde Firebase
     *
     * Si algo falla, NADA queda guardado (ni Firestore ni Dropbox)
     *
     * @param userId ID del usuario que crea la receta
     * @param addressId ID de la dirección a usar (desde users/{userId}/addresses/{addressId})
     * @param file Imagen de la receta (obligatorio)
     * @return recetaId, imagenUrl y datos de la receta creada
     */
    @PostMapping("/crear-con-imagen")
    public ResponseEntity<Map<String, Object>> crearRecetaConImagen(
            @RequestParam("userId") String userId,
            @RequestParam("addressId") String addressId,
            @RequestParam("file") MultipartFile file) {

        log.info("📝 Iniciando creación atómica de receta - Usuario: {}, Dirección: {}",
                userId, addressId);

        try {
            // Validaciones básicas
            if (userId == null || userId.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "userId es obligatorio"));
            }

            if (addressId == null || addressId.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "addressId es obligatorio"));
            }

            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "La imagen es obligatoria"));
            }

            // Validar tipo de archivo
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "El archivo debe ser una imagen"));
            }

            // Validar tamaño (máximo 10MB)
            long maxSize = 10 * 1024 * 1024; // 10MB
            if (file.getSize() > maxSize) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "La imagen no puede superar 10MB"));
            }

            log.info("✅ Imagen válida: {} ({} KB)", file.getOriginalFilename(), file.getSize() / 1024);

            // ⭐ El servicio obtiene datos del usuario Y la dirección desde Firebase
            Map<String, Object> resultado = recetaService.crearRecetaConImagenAtomica(
                    userId, addressId, file
            );

            log.info("✅ Receta creada exitosamente: {}", resultado.get("recetaId"));
            return ResponseEntity.status(HttpStatus.CREATED).body(resultado);

        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("❌ Validación fallida: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));

        } catch (DbxException e) {
            log.error("☁️ Error de Dropbox: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Error subiendo imagen al almacenamiento"));

        } catch (IOException e) {
            log.error("📁 Error de I/O: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error procesando el archivo"));

        } catch (Exception e) {
            log.error("💥 Error inesperado: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno al crear la receta"));
        }
    }

    /**
     * Health check del servicio de recetas
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "service", "API de recetas"
        ));
    }
}