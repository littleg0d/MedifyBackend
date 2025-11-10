package com.medify.medicamentos_backend.controller;

import com.dropbox.core.DbxException;
import com.medify.medicamentos_backend.service.DropboxService;
import com.medify.medicamentos_backend.service.RecetaImagenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/imagenes")
public class ImagenController {

    private static final Logger log = LoggerFactory.getLogger(ImagenController.class);

    private final DropboxService dropboxService;
    private final RecetaImagenService recetaImagenService;

    public ImagenController(DropboxService dropboxService, RecetaImagenService recetaImagenService) {
        this.dropboxService = dropboxService;
        this.recetaImagenService = recetaImagenService;
    }

    /**
     * Sube una imagen de receta a Dropbox y actualiza automáticamente Firestore
     *
     * @param recetaId ID de la receta en Firestore
     * @param userId ID del usuario (para validación de permisos)
     * @param file Imagen a subir
     * @param carpeta (Opcional) Subcarpeta dentro de /medify/imagenes (ej: "recetas", "medicamentos")
     * @return URL pública de la imagen y datos de la receta actualizada
     */
    @PostMapping("/subir")
    public ResponseEntity<Map<String, Object>> subirImagenReceta(
            @RequestParam("recetaId") String recetaId,
            @RequestParam("userId") String userId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "carpeta", required = false, defaultValue = "recetas") String carpeta) {

        if (!dropboxService.isConfigured()) {
            log.warn("Dropbox no está configurado");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Servicio de almacenamiento no disponible"));
        }

        try {
            log.info("Subiendo imagen para receta {} del usuario {}: {} ({}KB)",
                    recetaId, userId, file.getOriginalFilename(), file.getSize() / 1024);

            // El servicio maneja todo: validación, subida y actualización
            Map<String, Object> resultado = recetaImagenService.subirYActualizarReceta(
                    recetaId, userId, file, carpeta
            );

            log.info("✅ Imagen subida y receta actualizada exitosamente");
            return ResponseEntity.ok(resultado);

        } catch (IllegalArgumentException e) {
            log.warn("Validación fallida: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));

        } catch (SecurityException e) {
            log.warn("Acceso denegado: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));

        } catch (DbxException e) {
            log.error("Error de Dropbox: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Error subiendo imagen al almacenamiento"));

        } catch (Exception e) {
            log.error("Error inesperado: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno al procesar la imagen"));
        }
    }

    /**
     * Elimina una imagen de una receta (tanto de Dropbox como de Firestore)
     */
    @DeleteMapping("/eliminar")
    public ResponseEntity<Map<String, String>> eliminarImagenReceta(
            @RequestParam("recetaId") String recetaId,
            @RequestParam("userId") String userId) {

        if (!dropboxService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Servicio de almacenamiento no disponible"));
        }

        try {
            log.info("Eliminando imagen de receta {} (usuario: {})", recetaId, userId);
            
            recetaImagenService.eliminarImagenReceta(recetaId, userId);
            
            return ResponseEntity.ok(Map.of("message", "Imagen eliminada correctamente"));

        } catch (IllegalArgumentException e) {
            log.warn("Validación fallida: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));

        } catch (SecurityException e) {
            log.warn("Acceso denegado: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));

        } catch (DbxException e) {
            log.error("Error eliminando imagen: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "No se pudo eliminar la imagen"));

        } catch (Exception e) {
            log.error("Error inesperado: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno al eliminar la imagen"));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> status = new HashMap<>();
        status.put("status", "OK");
        status.put("service", "API de imágenes");
        status.put("dropboxConfigured", String.valueOf(dropboxService.isConfigured()));
        return ResponseEntity.ok(status);
    }
}
