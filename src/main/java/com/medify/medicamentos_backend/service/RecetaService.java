package com.medify.medicamentos_backend.service;

import com.dropbox.core.DbxException;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FieldValue;
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
 * Servicio para operaciones de recetas con transacciones atómicas
 */
@Service
public class RecetaService {

    private static final Logger log = LoggerFactory.getLogger(RecetaService.class);

    @Value("${firestore.timeout.seconds:10}")
    private long firestoreTimeoutSeconds;

    private final Firestore firestore;
    private final DropboxService dropboxService;

    public RecetaService(Firestore firestore, DropboxService dropboxService) {
        this.firestore = firestore;
        this.dropboxService = dropboxService;
    }

    /**
     * ⭐ MÉTODO ATÓMICO SIMPLIFICADO:
     * Solo recibe userId y file, obtiene todos los datos del usuario desde Firebase
     *
     * FLUJO TRANSACCIONAL:
     * 1. Obtiene datos completos del usuario desde Firebase
     * 2. Genera ID único para la receta
     * 3. Sube imagen a Dropbox con ese ID
     * 4. Crea documento en Firestore con la URL y datos del usuario
     * 5. Si falla (3) o (4), hace rollback completo
     *
     * VENTAJAS:
     * - O TODO funciona o NADA queda guardado
     * - No hay recetas huérfanas sin imagen
     * - No hay imágenes huérfanas en Dropbox
     * - Datos siempre consistentes con Firebase
     * - Frontend más simple (solo envía userId + file)
     *
     * @param userId ID del usuario
     * @param file Imagen de la receta (obligatorio)
     * @return Map con recetaId, imagenUrl, imagenPath y mensaje
     * @throws DbxException si falla Dropbox
     * @throws IOException si hay problema con el archivo
     */
    public Map<String, Object> crearRecetaConImagenAtomica(String userId, MultipartFile file)
            throws DbxException, IOException {

        log.info("🚀 Iniciando creación atómica de receta para usuario: {}", userId);

        // ====== VALIDACIONES INICIALES ======

        if (!dropboxService.isConfigured()) {
            throw new IllegalStateException(" ❌❌ DROPBOX NO DISPONIBLE");
        }

        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("userId es obligatorio");
        }

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("La imagen es obligatoria");
        }

        // ====== PASO 1: OBTENER DATOS DEL USUARIO DESDE FIREBASE ======

        log.info("🔍 Obteniendo datos del usuario desde Firebase...");
        Map<String, Object> userData = obtenerUsuario(userId);

        if (userData == null) {
            throw new IllegalArgumentException("Usuario no encontrado: " + userId);
        }

        // Extraer y validar datos requeridos
        String userName = extractString(userData, "displayName", "Nombre del usuario");
        String userEmail = extractString(userData, "email", "Email del usuario");
        String userDNI = extractString(userData, "dni", "DNI del usuario");
        String userPhone = extractStringOptional(userData, "phone");

        // ✅ Dirección del usuario (campo: "address")
        Map<String, String> userAddress = extractAddressFromUser(userData);
        if (userAddress == null || userAddress.isEmpty()) {
            throw new IllegalArgumentException("El usuario no tiene dirección configurada");
        }

        // Obra social del usuario
        Map<String, String> userObraSocial = extractObraSocial(userData, "obraSocial");
        if (userObraSocial == null || userObraSocial.isEmpty()) {
            throw new IllegalArgumentException("El usuario no tiene obra social configurada");
        }

        log.info("✅ Datos del usuario obtenidos: {} ({})", userName, userEmail);

        // ====== PASO 2: GENERAR ID ÚNICO ======

        DocumentReference recetaRef = firestore.collection("recetas").document();
        String recetaId = recetaRef.getId();

        log.info("🆔 ID generado: {}", recetaId);

        // Variables para rollback
        String dropboxPath = null;
        boolean firestoreCreado = false;

        try {
            // ====== PASO 3: SUBIR IMAGEN A DROPBOX ======

            log.info("☁️ Subiendo imagen a Dropbox...");
            Map<String, String> resultadoDropbox = dropboxService.subirImagen(file, "recetas");

            String imageUrl = resultadoDropbox.get("url");
            dropboxPath = resultadoDropbox.get("path");

            log.info("✅ Imagen subida: {}", dropboxPath);

            // ====== PASO 4: CREAR DOCUMENTO EN FIRESTORE ======

            log.info("💾 Creando documento en Firestore...");

            Map<String, Object> recetaData = new HashMap<>();

            // Datos básicos de la receta
            recetaData.put("userId", userId);
            recetaData.put("estado", "esperando_respuestas");
            recetaData.put("fechaCreacion", FieldValue.serverTimestamp());
            recetaData.put("cotizacionesCount", 0);

            // Datos de la imagen
            recetaData.put("imagenUrl", imageUrl);
            recetaData.put("imagenPath", dropboxPath);
            recetaData.put("imagenNombre", file.getOriginalFilename());
            recetaData.put("imagenSize", file.getSize());

            // ⭐ Datos del usuario (obtenidos desde Firebase)
            recetaData.put("userName", userName);
            recetaData.put("userEmail", userEmail);
            recetaData.put("userAddress", userAddress);
            recetaData.put("userDNI", userDNI);
            recetaData.put("userPhone", userPhone);
            recetaData.put("userObraSocial", userObraSocial);

            // Crear en Firestore con timeout
            recetaRef.set(recetaData).get(firestoreTimeoutSeconds, TimeUnit.SECONDS);
            firestoreCreado = true;

            log.info("✅ Receta {} creada exitosamente en Firestore", recetaId);

            // ====== PASO 5: RETORNAR RESULTADO ======

            Map<String, Object> resultado = new HashMap<>();
            resultado.put("recetaId", recetaId);
            resultado.put("imagenUrl", imageUrl);
            resultado.put("imagenPath", dropboxPath);
            resultado.put("fileName", file.getOriginalFilename());
            resultado.put("size", file.getSize());
            resultado.put("mensaje", "Receta creada exitosamente");

            log.info("🎉 Operación atómica completada exitosamente");
            return resultado;

        } catch (Exception e) {
            // ====== ROLLBACK EN CASO DE ERROR ======

            log.error("❌ Error en creación atómica, iniciando rollback...", e);

            // Si se subió la imagen pero falló Firestore, borrar de Dropbox
            if (dropboxPath != null && !firestoreCreado) {
                try {
                    log.warn("🧹 Limpiando imagen de Dropbox: {}", dropboxPath);
                    dropboxService.eliminarImagen(dropboxPath);
                    log.info("✅ Imagen eliminada de Dropbox (rollback exitoso)");
                } catch (DbxException dbxEx) {
                    log.error("💥 CRÍTICO: No se pudo hacer rollback de Dropbox: {}",
                            dbxEx.getMessage(), dbxEx);
                }
            }

            // Si se creó en Firestore pero hubo otro error, intentar borrar
            if (firestoreCreado) {
                try {
                    log.warn("🧹 Limpiando documento de Firestore: {}", recetaId);
                    recetaRef.delete().get(firestoreTimeoutSeconds, TimeUnit.SECONDS);
                    log.info("✅ Documento eliminado de Firestore (rollback exitoso)");
                } catch (Exception fsEx) {
                    log.error("💥 CRÍTICO: No se pudo hacer rollback de Firestore: {}",
                            fsEx.getMessage(), fsEx);
                }
            }

            // Re-lanzar la excepción original
            if (e instanceof DbxException) {
                throw (DbxException) e;
            } else if (e instanceof IOException) {
                throw (IOException) e;
            } else {
                throw new RuntimeException("Error en creación atómica de receta", e);
            }
        }
    }

    // ==================================================================================
    // 🔍 MÉTODOS AUXILIARES PRIVADOS
    // ==================================================================================

    /**
     * Obtiene un usuario desde Firestore
     */
    private Map<String, Object> obtenerUsuario(String userId) {
        try {
            DocumentSnapshot doc = firestore.collection("users")
                    .document(userId)
                    .get()
                    .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            if (!doc.exists()) {
                log.warn("⚠️ Usuario {} no encontrado", userId);
                return null;
            }

            return doc.getData();

        } catch (Exception e) {
            log.error("❌ Error obteniendo usuario {}", userId, e);
            throw new RuntimeException("Error al obtener usuario desde Firestore", e);
        }
    }

    /**
     * Extrae un string requerido del Map
     */
    private String extractString(Map<String, Object> data, String key, String fieldName) {
        Object value = data.get(key);
        if (value == null || value.toString().trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " es requerido");
        }
        return value.toString();
    }

    /**
     * Extrae un string opcional del Map
     */
    private String extractStringOptional(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value != null ? value.toString() : "";
    }

    /**
     * Extrae la dirección del USUARIO (campo: "address")
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> extractAddressFromUser(Map<String, Object> userData) {
        Object addressObj = userData.get("address"); // ✅ Usuario usa "address"

        if (addressObj == null) {
            log.warn("⚠️ Campo 'address' no encontrado en usuario");
            return null;
        }

        if (addressObj instanceof Map) {
            Map<?, ?> rawMap = (Map<?, ?>) addressObj;
            Map<String, String> address = new HashMap<>();

            address.put("street", getString(rawMap, "street"));
            address.put("city", getString(rawMap, "city"));
            address.put("province", getString(rawMap, "province"));
            address.put("postalCode", getString(rawMap, "postalCode"));

            log.debug("Dirección de usuario extraída: {}, {}",
                    address.get("street"), address.get("city"));
            return address;
        }

        log.warn("⚠️ Campo 'address' no es un Map válido");
        return null;
    }

    /**
     * Extrae obra social como Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> extractObraSocial(Map<String, Object> data, String key) {
        Object obraObj = data.get(key);
        if (obraObj == null) {
            return null;
        }

        if (obraObj instanceof Map) {
            Map<?, ?> rawMap = (Map<?, ?>) obraObj;
            Map<String, String> obra = new HashMap<>();

            obra.put("name", getString(rawMap, "name"));
            obra.put("number", getString(rawMap, "number"));

            return obra;
        }

        return null;
    }

    /**
     * Obtiene un string de un Map con claves desconocidas
     */
    private String getString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : "";
    }

    /**
     * Valida que una receta existe y pertenece al usuario
     * Útil para otros endpoints que necesiten verificar permisos
     */
    public boolean validarPropietario(String recetaId, String userId) {
        try {
            var doc = firestore.collection("recetas")
                    .document(recetaId)
                    .get()
                    .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            if (!doc.exists()) {
                return false;
            }

            String recetaUserId = doc.getString("userId");
            return userId.equals(recetaUserId);

        } catch (Exception e) {
            log.error("Error validando propietario de receta {}", recetaId, e);
            return false;
        }
    }
}