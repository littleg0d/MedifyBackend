package com.medify.medicamentos_backend.service;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.api.core.ApiFuture;
import com.medify.medicamentos_backend.dto.PedidoData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class FirebaseService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseService.class);

    @Value("${firestore.timeout.seconds:10}")
    private long firestoreTimeoutSeconds;

    @Value("${orders.pending.age.minutes:5}")
    private int pendingAgeMinutes;

    private final Firestore db;

    public FirebaseService(Firestore firestore) {
        this.db = firestore;
        log.debug("FirebaseService inicializado con Firestore inyectado");
    }

    // ==================================================================================
    // 🔍 OBTENER DATOS COMPLETOS PARA PEDIDO
    // ==================================================================================

    /**
     * Obtiene todos los datos necesarios para crear un pedido desde Firebase
     * @throws IllegalArgumentException si algún dato requerido no existe o es inválido
     */
    public PedidoData obtenerDatosCompletosParaPedido(
            String userId,
            String farmaciaId,
            String recetaId,
            String cotizacionId) {

        log.info("🔍 Obteniendo datos completos - User: {}, Farmacia: {}, Receta: {}, Cotización: {}",
                userId, farmaciaId, recetaId, cotizacionId);

        PedidoData datos = new PedidoData();

        // 1️⃣ Obtener datos del usuario
        Map<String, Object> userData = obtenerUsuario(userId);
        if (userData == null) {
            throw new IllegalArgumentException("Usuario no encontrado: " + userId);
        }

        datos.setUserId(userId);
        datos.setUserName(extractString(userData, "displayName", "userName"));
        datos.setUserEmail(extractString(userData, "email", "userEmail"));
        datos.setUserDNI(extractString(userData, "dni", "userDNI"));
        datos.setUserPhone(extractStringOptional(userData, "phone"));

        // ✅ Dirección del usuario (campo: "address")
        Map<String, String> userAddress = extractAddressFromUser(userData);
        if (userAddress == null || userAddress.isEmpty()) {
            throw new IllegalArgumentException("Dirección del usuario no encontrada");
        }
        datos.setUserAddress(userAddress);

        // Obra social del usuario
        Map<String, String> obraSocial = extractObraSocial(userData, "obraSocial");
        if (obraSocial == null || obraSocial.isEmpty()) {
            log.info("ℹ️ Usuario sin obra social configurada");
            obraSocial = null;
        }
        datos.setUserObraSocial(obraSocial);

        log.info("✅ Datos de usuario obtenidos: {}", datos.getUserName());

        // 2️⃣ Obtener datos de la farmacia
        Map<String, Object> farmData = obtenerFarmacia(farmaciaId);
        if (farmData == null) {
            throw new IllegalArgumentException("Farmacia no encontrada: " + farmaciaId);
        }

        datos.setFarmaciaId(farmaciaId);
        datos.setNombreComercial(extractString(farmData, "nombreComercial", "nombreComercial"));
        datos.setFarmEmail(extractString(farmData, "email", "farmEmail"));
        datos.setFarmPhone(extractString(farmData, "telefono", "farmPhone"));
        datos.setHorario(extractStringOptional(farmData, "horario"));

        // ✅ Dirección de la farmacia (campo: "direccion" - STRING directo)
        String farmAddress = extractAddressFromFarmacia(farmData);
        if (farmAddress == null || farmAddress.isEmpty()) {
            throw new IllegalArgumentException("Dirección de la farmacia no encontrada");
        }
        datos.setFarmAddress(farmAddress);

        log.info("✅ Datos de farmacia obtenidos: {}", datos.getNombreComercial());

        // 3️⃣ Obtener datos de la receta
        Map<String, Object> recetaData = obtenerReceta(recetaId);
        if (recetaData == null) {
            throw new IllegalArgumentException("Receta no encontrada: " + recetaId);
        }

        // Validar estado de la receta
        String estadoReceta = (String) recetaData.get("estado");
        if (!"farmacias_respondiendo".equals(estadoReceta)) {
            throw new IllegalArgumentException(
                    "La receta no está en estado válido para procesar el pago. Estado actual: " + estadoReceta
            );
        }

        datos.setRecetaId(recetaId);
        datos.setImagenUrl(extractString(recetaData, "imagenUrl", "imagenUrl"));

        log.info("✅ Datos de receta obtenidos (estado: {}) - Imagen: {}", estadoReceta, datos.getImagenUrl());

        // 4️⃣ Obtener datos de la cotización
        Map<String, Object> cotizacionData = obtenerCotizacion(recetaId, cotizacionId);
        if (cotizacionData == null) {
            throw new IllegalArgumentException("Cotización no encontrada: " + cotizacionId);
        }

        // Validar estado de la cotización
        String estadoCotizacion = (String) cotizacionData.get("estado");
        if (!"cotizado".equals(estadoCotizacion)) {
            throw new IllegalArgumentException(
                    "La cotización no está en estado válido. Estado actual: " + estadoCotizacion
            );
        }

        datos.setCotizacionId(cotizacionId);
        datos.setDescripcion(extractStringOptional(cotizacionData, "descripcion"));

        // Extraer y validar precio
        Double precio = extractPrecio(cotizacionData);
        if (precio == null || precio <= 0) {
            throw new IllegalArgumentException("Precio inválido en la cotización: " + precio);
        }
        datos.setPrecio(precio);

        log.info("✅ Datos de cotización obtenidos - Precio: ${}", precio);

        return datos;
    }

    // ==================================================================================
    // 🔍 MÉTODOS DE CONSULTA AUXILIARES
    // ==================================================================================

    /**
     * Obtiene un usuario desde Firestore
     */
    private Map<String, Object> obtenerUsuario(String userId) {
        try {
            DocumentSnapshot doc = db.collection("users")
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
     * Obtiene una farmacia desde Firestore
     */
    private Map<String, Object> obtenerFarmacia(String farmaciaId) {
        try {
            DocumentSnapshot doc = db.collection("farmacias")
                    .document(farmaciaId)
                    .get()
                    .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            if (!doc.exists()) {
                log.warn("⚠️ Farmacia {} no encontrada", farmaciaId);
                return null;
            }

            return doc.getData();

        } catch (Exception e) {
            log.error("❌ Error obteniendo farmacia {}", farmaciaId, e);
            throw new RuntimeException("Error al obtener farmacia desde Firestore", e);
        }
    }

    /**
     * Obtiene una receta desde Firestore
     */
    private Map<String, Object> obtenerReceta(String recetaId) {
        try {
            DocumentSnapshot doc = db.collection("recetas")
                    .document(recetaId)
                    .get()
                    .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            if (!doc.exists()) {
                log.warn("⚠️ Receta {} no encontrada", recetaId);
                return null;
            }

            Map<String, Object> data = doc.getData();
            log.debug("Receta {} obtenida correctamente", recetaId);
            return data;

        } catch (Exception e) {
            log.error("❌ Error obteniendo receta {}", recetaId, e);
            throw new RuntimeException("Error al obtener receta desde Firestore", e);
        }
    }

    /**
     * Obtiene una cotización desde Firestore
     */
    private Map<String, Object> obtenerCotizacion(String recetaId, String cotizacionId) {
        try {
            DocumentSnapshot doc = db.collection("recetas")
                    .document(recetaId)
                    .collection("cotizaciones")
                    .document(cotizacionId)
                    .get()
                    .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            if (!doc.exists()) {
                log.warn("⚠️ Cotización {} no encontrada en receta {}", cotizacionId, recetaId);
                return null;
            }

            Map<String, Object> data = doc.getData();
            log.debug("Cotización {} obtenida correctamente", cotizacionId);
            return data;

        } catch (Exception e) {
            log.error("❌ Error obteniendo cotización {}/{}", recetaId, cotizacionId, e);
            throw new RuntimeException("Error al obtener cotización desde Firestore", e);
        }
    }

    // ==================================================================================
    // 🛠️ MÉTODOS AUXILIARES DE EXTRACCIÓN
    // ==================================================================================

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
     * Extrae la dirección de la FARMACIA (campo: "direccion" como STRING directo)
     * NO es un Map, es simplemente un String
     */
    private String extractAddressFromFarmacia(Map<String, Object> farmData) {
        Object direccionObj = farmData.get("direccion"); // ✅ Farmacia usa "direccion"

        if (direccionObj == null) {
            log.warn("⚠️ Campo 'direccion' no encontrado en farmacia");
            return "";
        }

        String direccionString = direccionObj.toString().trim();

        if (direccionString.isEmpty()) {
            log.warn("⚠️ Campo 'direccion' está vacío en farmacia");
            return "";
        }

        log.debug("Dirección de farmacia extraída: {}", direccionString);
        return direccionString;
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
     * Extrae el precio desde los datos de la cotización
     */
    private Double extractPrecio(Map<String, Object> cotizacion) {
        Object precioObj = cotizacion.get("precio");

        if (precioObj == null) {
            log.warn("⚠️ Campo 'precio' no encontrado en cotización");
            return null;
        }

        if (precioObj instanceof Number) {
            return ((Number) precioObj).doubleValue();
        }

        if (precioObj instanceof String) {
            try {
                return Double.parseDouble((String) precioObj);
            } catch (NumberFormatException e) {
                log.warn("No se pudo convertir precio string: {}", precioObj);
                return null;
            }
        }

        log.warn("⚠️ Tipo de precio no soportado: {}", precioObj.getClass().getName());
        return null;
    }

    // ==================================================================================
    // 🔒 CREAR PEDIDO CON TRANSACCIÓN
    // ==================================================================================

    /**
     * Crea un pedido de forma atómica usando los datos completos
     */
    public String crearPedidoConTransaccion(PedidoData datos) {
        try {
            ApiFuture<String> transactionFuture = db.runTransaction(transaction -> {

                // 1️⃣ Buscar pedido activo DENTRO de la transacción
                Query query = db.collection("pedidos")
                        .whereEqualTo("userId", datos.getUserId())
                        .whereEqualTo("recetaId", datos.getRecetaId())
                        .whereIn("estado", Arrays.asList(
                                "pendiente_de_pago",
                                "pagado",
                                "pendiente"
                        ))
                        .orderBy("fechaCreacion", Query.Direction.DESCENDING)
                        .limit(1);

                ApiFuture<QuerySnapshot> queryFuture = transaction.get(query);
                QuerySnapshot snapshot = queryFuture.get();

                // 2️⃣ Si existe, validar estado y tiempo
                if (!snapshot.isEmpty()) {
                    DocumentSnapshot pedidoExistente = snapshot.getDocuments().get(0);
                    String estado = pedidoExistente.getString("estado");
                    Timestamp fechaCreacion = pedidoExistente.getTimestamp("fechaCreacion");

                    log.warn("⚠️ Pedido existente encontrado: {} - Estado: {}",
                            pedidoExistente.getId(), estado);

                    if ("pagado".equals(estado)) {
                        throw new IllegalStateException(
                                "Ya existe un pedido pagado para esta receta"
                        );
                    }

                    if (fechaCreacion != null) {
                        long minutosTranscurridos = ChronoUnit.MINUTES.between(
                                fechaCreacion.toDate().toInstant(),
                                Instant.now()
                        );

                        if (minutosTranscurridos < pendingAgeMinutes) {
                            long minutosRestantes = pendingAgeMinutes - minutosTranscurridos;
                            throw new IllegalStateException(
                                    "Ya existe un pedido en proceso. Debes esperar " +
                                            minutosRestantes + " minuto(s) más"
                            );
                        }

                        log.info("✅ Pedido existente ha expirado ({} min), permitiendo nuevo pedido",
                                minutosTranscurridos);
                    }
                }

                // 3️⃣ Crear el pedido dentro de la transacción
                DocumentReference pedidoRef = db.collection("pedidos").document();
                Map<String, Object> pedidoData = buildPedidoDataFromDto(datos);
                transaction.set(pedidoRef, pedidoData);

                log.info("✅ Pedido {} creado dentro de transacción", pedidoRef.getId());
                return pedidoRef.getId();
            });

            String pedidoId = transactionFuture.get(firestoreTimeoutSeconds + 5, TimeUnit.SECONDS);
            log.info("✅ Transacción completada exitosamente - Pedido: {}", pedidoId);
            return pedidoId;

        } catch (IllegalStateException e) {
            log.warn("🚫 Validación de negocio falló: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("❌ Error en transacción de creación de pedido", e);
            throw new RuntimeException("Error al crear pedido en Firestore", e);
        }
    }

    /**
     * Construye el Map con los datos del pedido desde el DTO
     * Incluye TODOS los datos necesarios para el pedido completo
     */
    private Map<String, Object> buildPedidoDataFromDto(PedidoData datos) {
        Map<String, Object> data = new HashMap<>();

        // IDs principales
        data.put("recetaId", datos.getRecetaId());
        data.put("farmaciaId", datos.getFarmaciaId());
        data.put("userId", datos.getUserId());
        data.put("cotizacionId", datos.getCotizacionId());

        // Datos del usuario
        data.put("userName", datos.getUserName());
        data.put("userEmail", datos.getUserEmail());
        data.put("userDNI", datos.getUserDNI());
        data.put("userPhone", datos.getUserPhone());
        data.put("userAddress", datos.getUserAddress());
        data.put("userObraSocial", datos.getUserObraSocial());

        // Datos de la farmacia
        data.put("nombreComercial", datos.getNombreComercial());
        data.put("farmEmail", datos.getFarmEmail());
        data.put("farmPhone", datos.getFarmPhone());
        data.put("horario", datos.getHorario());
        data.put("farmAddress", datos.getFarmAddress()); // ✅ Ya es String directo

        // Datos del pedido
        data.put("precio", datos.getPrecio());
        data.put("descripcion", datos.getDescripcion());
        data.put("imagenUrl", datos.getImagenUrl());
        data.put("estado", "pendiente_de_pago");
        data.put("fechaCreacion", FieldValue.serverTimestamp());
        data.put("fechaPago", null);

        return data;
    }

    // ==================================================================================
    // MÉTODOS DE VALIDACIÓN Y GESTIÓN DE PEDIDOS
    // ==================================================================================

    /**
     * Verifica si existe un pedido activo para una receta
     */
    public boolean existePedidoActivoPorReceta(String recetaId, String userId) {
        try {
            Query query = db.collection("pedidos")
                    .whereEqualTo("userId", userId)
                    .whereEqualTo("recetaId", recetaId)
                    .whereIn("estado", Arrays.asList(
                            "pendiente_de_pago",
                            "pagado",
                            "pendiente"
                    ))
                    .orderBy("fechaCreacion", Query.Direction.DESCENDING)
                    .limit(1);

            QuerySnapshot snapshot = query.get().get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            if (snapshot.isEmpty()) {
                return false;
            }

            DocumentSnapshot pedido = snapshot.getDocuments().get(0);
            String estado = pedido.getString("estado");
            Timestamp fechaCreacion = pedido.getTimestamp("fechaCreacion");

            log.info("🔍 Pedido existente encontrado: {} - Estado: {}", pedido.getId(), estado);

            if ("pagado".equals(estado)) {
                log.info("🔒 Receta bloqueada: pedido ya pagado");
                return true;
            }

            if (fechaCreacion != null) {
                long minutosTranscurridos = ChronoUnit.MINUTES.between(
                        fechaCreacion.toDate().toInstant(),
                        Instant.now()
                );

                boolean bloqueado = minutosTranscurridos < pendingAgeMinutes;

                if (bloqueado) {
                    log.info("🔒 Receta bloqueada: {} minutos transcurridos (límite: {})",
                            minutosTranscurridos, pendingAgeMinutes);
                } else {
                    log.info("✅ Pedido expirado ({} min), receta disponible",
                            minutosTranscurridos);
                }

                return bloqueado;
            }

            log.warn("⚠️ Pedido sin fechaCreacion, permitiendo por defecto");
            return false;

        } catch (Exception e) {
            log.error("❌ Error verificando pedidos activos para receta {}", recetaId, e);
            return true;
        }
    }

    /**
     * Busca pedidos pendientes antiguos para limpieza
     */
    public List<String> buscarPedidosPendientesAntiguos(int minutes) {
        long cutoffSeconds = Instant.now()
                .minus(Duration.ofMinutes(minutes))
                .getEpochSecond();

        Timestamp cutoff = Timestamp.ofTimeSecondsAndNanos(cutoffSeconds, 0);
        var query = db.collection("pedidos")
                .whereEqualTo("estado", "pendiente_de_pago")
                .whereLessThan("fechaCreacion", cutoff);

        try {
            var querySnapshot = query.get().get(firestoreTimeoutSeconds, TimeUnit.SECONDS);
            List<String> ids = new ArrayList<>();
            querySnapshot.getDocuments().forEach(doc -> ids.add(doc.getId()));
            return ids;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Consulta a Firestore interrumpida: {}", e.getMessage());
            return Collections.emptyList();
        } catch (ExecutionException e) {
            log.error("Error en la ejecución de la consulta a Firestore: {}", e.getMessage());
            return Collections.emptyList();
        } catch (TimeoutException e) {
            log.error("Timeout esperando la respuesta de Firestore: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Marca un pedido como abandonado
     */
    public void marcarPedidoAbandonado(String pedidoId) {
        try {
            Map<String, Object> updates = new HashMap<>();
            updates.put("estado", "abandonada");
            updates.put("fechaCierre", FieldValue.serverTimestamp());

            db.collection("pedidos").document(pedidoId)
                    .update(updates)
                    .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            log.info("🗑️ Pedido {} marcado como abandonado", pedidoId);
        } catch (Exception e) {
            log.error("❌ Error marcando pedido {} como abandonado", pedidoId, e);
            throw new RuntimeException("Error marcando pedido como abandonado", e);
        }
    }

    /**
     * Borra un pedido completamente
     */
    public void borrarPedido(String pedidoId) {
        try {
            db.collection("pedidos").document(pedidoId)
                    .delete()
                    .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            log.info("🗑️ Pedido {} eliminado", pedidoId);
        } catch (Exception e) {
            log.error("❌ Error borrando pedido {}", pedidoId, e);
            throw new RuntimeException("Error al borrar pedido", e);
        }
    }

    /**
     * Actualiza campos específicos de un pedido
     */
    public void actualizarPedido(String pedidoId, Map<String, Object> updates) {
        try {
            db.collection("pedidos").document(pedidoId)
                    .update(updates)
                    .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            log.info("✏️ Pedido {} actualizado", pedidoId);
        } catch (Exception e) {
            log.error("❌ Error actualizando pedido {}", pedidoId, e);
            throw new RuntimeException("Error al actualizar pedido", e);
        }
    }

    /**
     * Marca un pedido como pagado de forma idempotente
     */
    public boolean marcarPedidoComoPagadoIdempotente(String pedidoId, String paymentId, String status) {
        try {
            Boolean updated = db.runTransaction(transaction -> {
                DocumentReference pedidoRef = db.collection("pedidos").document(pedidoId);
                DocumentSnapshot snapshot = transaction.get(pedidoRef)
                        .get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

                if (!snapshot.exists()) {
                    log.warn("⚠️ Pedido {} no existe", pedidoId);
                    return false;
                }

                String estadoActual = snapshot.getString("estado");
                String paymentIdActual = snapshot.getString("paymentId");

                if ("pagado".equals(estadoActual) && paymentId.equals(paymentIdActual)) {
                    log.info("ℹ️ Pedido {} ya pagado con payment {}, operación idempotente",
                            pedidoId, paymentId);
                    return false;
                }

                if ("pagado".equals(estadoActual)) {
                    log.error("⚠️ Pedido {} ya pagado con payment {}, intento duplicado con {}",
                            pedidoId, paymentIdActual, paymentId);
                    return false;
                }

                Map<String, Object> updates = new HashMap<>();
                updates.put("estado", "pagado");
                updates.put("paymentId", paymentId);
                updates.put("paymentStatus", status);
                updates.put("fechaPago", FieldValue.serverTimestamp());
                transaction.update(pedidoRef, updates);

                String recetaId = snapshot.getString("recetaId");
                if (recetaId != null && !recetaId.isBlank()) {
                    DocumentReference recetaRef = db.collection("recetas").document(recetaId);
                    transaction.update(recetaRef, "estado", "finalizada");
                    log.debug("🔄 Receta {} marcada como finalizada", recetaId);
                }

                return true;

            }).get(firestoreTimeoutSeconds + 2, TimeUnit.SECONDS);

            if (Boolean.TRUE.equals(updated)) {
                log.info("✅ Pedido {} marcado como pagado (paymentId: {})", pedidoId, paymentId);
            }

            return Boolean.TRUE.equals(updated);

        } catch (Exception e) {
            log.error("❌ Error en transacción de pago para pedido {}", pedidoId, e);
            throw new RuntimeException("Error al marcar pedido como pagado", e);
        }
    }
}