package com.medify.medicamentos_backend.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.medify.medicamentos_backend.dto.CotizacionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Servicio para gestionar las cotizaciones de las farmacias
 */
@Service
public class CotizacionService {

    private static final Logger log = LoggerFactory.getLogger(CotizacionService.class);

    @Value("${firestore.timeout.seconds:10}")
    private long firestoreTimeoutSeconds;

    private final Firestore db;

    public CotizacionService(Firestore firestore) {
        this.db = firestore;
    }

    /**
     * Procesa la respuesta de una farmacia a una receta
     *
     * Operaciones:
     * 1. Valida que la receta exista y esté en estado válido
     * 2. Obtiene datos completos de la farmacia desde Firebase
     * 3. Crea la cotización en /recetas/{recetaId}/cotizaciones
     * 4. Registra la farmacia en /recetas/{recetaId}/farmaciasRespondieron
     * 5. Actualiza el contador cotizacionesCount
     * 6. Actualiza el estado de la receta según corresponda
     *
     * @param request Datos de la cotización
     * @return ID de la cotización creada
     */
    public String responderReceta(CotizacionRequest request) {
        try {
            // 1️⃣ Validar que la receta existe y está en estado válido
            DocumentReference recetaRef = db.collection("recetas").document(request.getRecetaId());
            DocumentSnapshot recetaSnap = recetaRef.get()
                    .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            if (!recetaSnap.exists()) {
                throw new IllegalArgumentException("Receta no encontrada: " + request.getRecetaId());
            }

            String estadoReceta = recetaSnap.getString("estado");
            if ("finalizada".equals(estadoReceta)) {
                throw new IllegalStateException("La receta ya está finalizada");
            }

            // 2️⃣ Verificar si la farmacia ya respondió
            DocumentReference farmaciaRespondioRef = db.collection("recetas")
                    .document(request.getRecetaId())
                    .collection("farmaciasRespondieron")
                    .document(request.getFarmaciaId());

            DocumentSnapshot farmaciaRespondioSnap = farmaciaRespondioRef.get()
                    .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            if (farmaciaRespondioSnap.exists()) {
                throw new IllegalStateException("La farmacia ya respondió a esta receta");
            }

            // 3️⃣ Obtener datos completos de la farmacia
            DocumentReference farmaciaRef = db.collection("farmacias")
                    .document(request.getFarmaciaId());
            DocumentSnapshot farmaciaSnap = farmaciaRef.get()
                    .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            if (!farmaciaSnap.exists()) {
                throw new IllegalArgumentException("Farmacia no encontrada: " + request.getFarmaciaId());
            }

            Map<String, Object> farmaciaData = farmaciaSnap.getData();
            String nombreComercial = extractString(farmaciaData, "nombreComercial");
            String direccion = extractString(farmaciaData, "direccion");
            String email = extractString(farmaciaData, "email");
            String telefono = extractStringOptional(farmaciaData, "telefono");

            log.info("📋 Datos de farmacia obtenidos: {} ({})", nombreComercial, email);

            // 4️⃣ Crear la cotización
            DocumentReference cotizacionRef = db.collection("recetas")
                    .document(request.getRecetaId())
                    .collection("cotizaciones")
                    .document();

            Map<String, Object> cotizacionData = new HashMap<>();
            cotizacionData.put("farmaciaId", request.getFarmaciaId());
            cotizacionData.put("nombreComercial", nombreComercial);
            cotizacionData.put("direccion", direccion);
            cotizacionData.put("email", email);
            cotizacionData.put("telefono", telefono);
            cotizacionData.put("estado", request.getEstado());
            cotizacionData.put("fechaCreacion", FieldValue.serverTimestamp());

            // Campos opcionales (solo si estado = "cotizado")
            if ("cotizado".equals(request.getEstado())) {
                cotizacionData.put("descripcion", request.getDescripcion());
                cotizacionData.put("precio", request.getPrecio());
            } else {
                cotizacionData.put("descripcion", null);
                cotizacionData.put("precio", null);
            }

            cotizacionRef.set(cotizacionData).get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            log.info("✅ Cotización creada: {}", cotizacionRef.getId());

            // 5️⃣ Registrar en farmaciasRespondieron con TODOS los datos
            Map<String, Object> farmaciaRespondioData = new HashMap<>();
            farmaciaRespondioData.put("farmaciaId", request.getFarmaciaId());
            farmaciaRespondioData.put("nombreComercial", nombreComercial);
            farmaciaRespondioData.put("cotizacionId", cotizacionRef.getId());
            farmaciaRespondioData.put("estado", request.getEstado());
            farmaciaRespondioData.put("fechaRespuesta", FieldValue.serverTimestamp());

            // ✅ AGREGAR: Duplicar datos para consulta rápida
            if ("cotizado".equals(request.getEstado())) {
                farmaciaRespondioData.put("descripcion", request.getDescripcion());
                farmaciaRespondioData.put("precio", request.getPrecio());
            } else {
                farmaciaRespondioData.put("descripcion", null);
                farmaciaRespondioData.put("precio", null);
            }

            farmaciaRespondioRef.set(farmaciaRespondioData)
                    .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            log.info("✅ Farmacia registrada en farmaciasRespondieron");

            // 6️⃣ Incrementar contador de cotizaciones
            recetaRef.update("cotizacionesCount", FieldValue.increment(1))
                    .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            // 7️⃣ Actualizar estado de la receta si es la primera respuesta
            if ("esperando_respuestas".equals(estadoReceta)) {
                recetaRef.update("estado", "farmacias_respondiendo")
                        .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);
                log.info("📝 Estado de receta actualizado a: farmacias_respondiendo");
            }

            return cotizacionRef.getId();

        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("⚠️ Validación fallida: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("❌ Error al procesar respuesta de farmacia", e);
            throw new RuntimeException("Error al crear cotización", e);
        }
    }

    // ==================================================================================
    // 🛠️ MÉTODOS AUXILIARES
    // ==================================================================================

    /**
     * Extrae un string requerido del Map
     */
    private String extractString(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null || value.toString().trim().isEmpty()) {
            throw new IllegalArgumentException(key + " es requerido");
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
}