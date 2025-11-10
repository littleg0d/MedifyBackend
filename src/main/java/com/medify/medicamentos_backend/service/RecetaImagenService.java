package com.medify.medicamentos_backend.service;

import com.dropbox.core.DbxException;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Servicio que coordina la subida de imágenes con la actualización de recetas en Firestore
 */
@Service
public class RecetaImagenService {

    private static final Logger log = LoggerFactory.getLogger(RecetaImagenService.class);

    @Value("${firestore.timeout.seconds:10}")
    private long firestoreTimeoutSeconds;

    private final Firestore db;
    private final DropboxService dropboxService;

    public RecetaImagenService(Firestore db, DropboxService dropboxService) {
        this.db = db;
        this.dropboxService = dropboxService;
    }

    /**
     * Sube una imagen a Dropbox y actualiza la receta en Firestore de forma atómica
     *
     * @param recetaId ID de la receta
     * @param userId ID del usuario (para validar permisos)
     * @param file Archivo de imagen
     * @param carpeta Subcarpeta en Dropbox
     * @return Map con url, path y datos de la receta
     */
    public Map<String, Object> subirYActualizarReceta(
            String recetaId,
            String userId,
            MultipartFile file,
            String carpeta) throws IOException, DbxException {

        // 1. Validar que la receta existe y pertenece al usuario
        DocumentSnapshot receta = validarReceta(recetaId, userId);

        // 2. Si ya tiene imagen, eliminar la antigua primero
        String imagenAntigua = receta.getString("imagenPath");
        if (imagenAntigua != null && !imagenAntigua.isBlank()) {
            log.info("Eliminando imagen antigua: {}", imagenAntigua);
            try {
                dropboxService.eliminarImagen(imagenAntigua);
            } catch (DbxException e) {
                log.warn("No se pudo eliminar imagen antigua: {}", e.getMessage());
                // Continuar de todos modos
            }
        }

        // 3. Subir nueva imagen a Dropbox
        Map<String, String> resultadoDropbox = dropboxService.subirImagen(file, carpeta);
        String imageUrl = resultadoDropbox.get("url");
        String imagePath = resultadoDropbox.get("path");

        // 4. Actualizar Firestore con la nueva imagen
        try {
            Map<String, Object> updates = new HashMap<>();
            updates.put("imagenUrl", imageUrl);
            updates.put("imagenPath", imagePath);
            updates.put("imagenNombre", file.getOriginalFilename());
            updates.put("imagenSize", file.getSize());

            db.collection("recetas").document(recetaId)
                    .update(updates)
                    .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            log.info("✅ Receta {} actualizada con nueva imagen", recetaId);

        } catch (Exception e) {
            // Si falla Firestore, intentar eliminar la imagen que acabamos de subir
            log.error("Error actualizando Firestore, eliminando imagen subida", e);
            try {
                dropboxService.eliminarImagen(imagePath);
            } catch (DbxException dbxEx) {
                log.error("No se pudo hacer rollback de la imagen: {}", dbxEx.getMessage());
            }
            throw new RuntimeException("Error actualizando receta en Firestore", e);
        }

        // 5. Retornar resultado
        Map<String, Object> resultado = new HashMap<>();
        resultado.put("recetaId", recetaId);
        resultado.put("imagenUrl", imageUrl);
        resultado.put("imagenPath", imagePath);
        resultado.put("fileName", file.getOriginalFilename());
        resultado.put("size", file.getSize());
        resultado.put("message", "Imagen subida y receta actualizada exitosamente");

        return resultado;
    }

    /**
     * Elimina la imagen de una receta (Dropbox + Firestore)
     */
    public void eliminarImagenReceta(String recetaId, String userId) throws DbxException {
        // 1. Validar receta
        DocumentSnapshot receta = validarReceta(recetaId, userId);

        // 2. Obtener path de la imagen
        String imagenPath = receta.getString("imagenPath");
        if (imagenPath == null || imagenPath.isBlank()) {
            throw new IllegalArgumentException("La receta no tiene imagen asociada");
        }

        // 3. Eliminar de Dropbox
        dropboxService.eliminarImagen(imagenPath);
        log.info("Imagen eliminada de Dropbox: {}", imagenPath);

        // 4. Actualizar Firestore (remover campos de imagen)
        try {
            Map<String, Object> updates = new HashMap<>();
            updates.put("imagenUrl", null);
            updates.put("imagenPath", null);
            updates.put("imagenNombre", null);
            updates.put("imagenSize", null);

            db.collection("recetas").document(recetaId)
                    .update(updates)
                    .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            log.info("✅ Referencias de imagen eliminadas de la receta {}", recetaId);

        } catch (Exception e) {
            log.error("Error actualizando Firestore al eliminar imagen", e);
            throw new RuntimeException("Error actualizando receta en Firestore", e);
        }
    }

    /**
     * Valida que la receta existe y pertenece al usuario
     */
    private DocumentSnapshot validarReceta(String recetaId, String userId) {
        if (recetaId == null || recetaId.isBlank()) {
            throw new IllegalArgumentException("recetaId es requerido");
        }

        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId es requerido");
        }

        try {
            DocumentReference recetaRef = db.collection("recetas").document(recetaId);
            DocumentSnapshot receta = recetaRef.get()
                    .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            if (!receta.exists()) {
                throw new IllegalArgumentException("La receta no existe");
            }

            // Validar que la receta pertenece al usuario
            String recetaUserId = receta.getString("userId");
            if (!userId.equals(recetaUserId)) {
                log.warn("⚠️ Usuario {} intentó acceder a receta {} que pertenece a {}",
                        userId, recetaId, recetaUserId);
                throw new SecurityException("No tienes permiso para modificar esta receta");
            }

            log.debug("✅ Receta {} validada para usuario {}", recetaId, userId);
            return receta;

        } catch (SecurityException | IllegalArgumentException e) {
            throw e; // Re-lanzar estos tal cual
        } catch (Exception e) {
            log.error("Error validando receta {}: {}", recetaId, e.getMessage(), e);
            throw new RuntimeException("Error al validar la receta", e);
        }
    }

    }