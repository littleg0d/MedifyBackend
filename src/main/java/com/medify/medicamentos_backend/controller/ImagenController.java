package com.medify.medicamentos_backend.controller;

import com.dropbox.core.DbxException;
import com.medify.medicamentos_backend.service.DropboxService;
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

    public ImagenController(DropboxService dropboxService) {
        this.dropboxService = dropboxService;
    }

    /**
     * Sube una imagen a Dropbox y devuelve el link pÃºblico
     *
     * @param file Imagen a subir
     * @param carpeta (Opcional) Subcarpeta dentro de /medify/imagenes
     * @return URL pÃºblica de la imagen
     */
    @PostMapping("/subir")
    public ResponseEntity<Map<String, String>> subirImagen(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "carpeta", required = false) String carpeta) {

        if (!dropboxService.isConfigured()) {
            log.warn("Dropbox no estÃ¡ configurado");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Servicio de almacenamiento no disponible"));
        }

        try {
            log.info("Subiendo imagen: {} ({}KB)",
                    file.getOriginalFilename(),
                    file.getSize() / 1024);

            String url = dropboxService.subirImagen(file, carpeta);

            Map<String, String> response = new HashMap<>();
            response.put("url", url);
            response.put("fileName", file.getOriginalFilename());
            response.put("size", String.valueOf(file.getSize()));

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("ValidaciÃ³n fallida: {}", e.getMessage());
            return ResponseEntity.badRequest()
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
     * Elimina una imagen de Dropbox
     */
    @DeleteMapping("/eliminar")
    public ResponseEntity<Map<String, String>> eliminarImagen(
            @RequestParam("path") String dropboxPath) {

        if (!dropboxService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Servicio de almacenamiento no disponible"));
        }

        try {
            dropboxService.eliminarImagen(dropboxPath);
            return ResponseEntity.ok(Map.of("message", "Imagen eliminada correctamente"));

        } catch (DbxException e) {
            log.error("Error eliminando imagen: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "No se pudo eliminar la imagen"));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> status = new HashMap<>();
        status.put("status", "OK");
        status.put("service", "API de imÃ¡genes");
        status.put("dropboxConfigured", String.valueOf(dropboxService.isConfigured()));
        return ResponseEntity.ok(status);
    }
}