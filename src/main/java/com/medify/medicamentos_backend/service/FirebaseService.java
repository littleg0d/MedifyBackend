package com.medify.medicamentos_backend.service;

import com.medify.medicamentos_backend.dto.Address;
import org.springframework.beans.factory.annotation.Value;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.api.core.ApiFuture;
import com.medify.medicamentos_backend.dto.PreferenciaRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

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
    // 🔒 CREAR PEDIDO CON TRANSACCIÓN (SOLUCIONA RACE CONDITION)
    // ==================================================================================

    /**
     * Crea un pedido de forma atómica, verificando dentro de una transacción
     * que no exista un pedido activo para evitar race conditions.
     *
     * @throws IllegalStateException si ya existe un pedido activo y válido
     */
    public String crearPedidoConTransaccion(PreferenciaRequest req, Double precio) {
        try {
            // 🔒 Ejecutar TODO dentro de una transacción atómica
            ApiFuture<String> transactionFuture = db.runTransaction(transaction -> {

                // 1️⃣ Buscar pedido activo DENTRO de la transacción
                Query query = db.collection("pedidos")
                        .whereEqualTo("userId", req.getUserId())
                        .whereEqualTo("recetaId", req.getRecetaId())
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

                    // Si está pagado, siempre rechazar
                    if ("pagado".equals(estado)) {
                        throw new IllegalStateException(
                                "Ya existe un pedido pagado para esta receta"
                        );
                    }

                    // Si está pendiente, validar tiempo de expiración
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
                Map<String, Object> pedidoData = buildPedidoData(req, precio);
                transaction.set(pedidoRef, pedidoData);

                log.info("✅ Pedido {} creado dentro de transacción", pedidoRef.getId());
                return pedidoRef.getId();
            });

            // Esperar a que la transacción termine
            String pedidoId = transactionFuture.get(firestoreTimeoutSeconds + 5, TimeUnit.SECONDS);
            log.info("✅ Transacción completada exitosamente - Pedido: {}", pedidoId);
            return pedidoId;

        } catch (IllegalStateException e) {
            // Re-lanzar excepciones de validación de negocio
            log.warn("🚫 Validación de negocio falló: {}", e.getMessage());
            throw e;

        } catch (Exception e) {
            log.error("❌ Error en transacción de creación de pedido", e);
            throw new RuntimeException("Error al crear pedido en Firestore", e);
        }
    }

    /**
     * Construye el Map con los datos del pedido
     */
    private Map<String, Object> buildPedidoData(PreferenciaRequest req, Double precio) {
        Map<String, Object> data = new HashMap<>();
        data.put("recetaId", req.getRecetaId());
        data.put("farmaciaId", req.getFarmaciaId());
        data.put("userId", req.getUserId());

        Address direccion = req.getDireccion();
        Map<String, Object> addressMap = new HashMap<>();
        addressMap.put("street", direccion.getStreet());
        addressMap.put("city", direccion.getCity());
        addressMap.put("province", direccion.getProvince());
        addressMap.put("postalCode", direccion.getPostalCode());
        data.put("addressUser", addressMap);

        data.put("precio", precio);
        data.put("cotizacionId", req.getCotizacionId());
        data.put("nombreComercial", req.getNombreComercial());
        data.put("imagenUrl", req.getImagenUrl());
        data.put("estado", "pendiente_de_pago");
        data.put("fechaCreacion", FieldValue.serverTimestamp());
        data.put("fechaPago", null);

        return data;
    }

    /**
     * Verifica si existe un pedido activo para una receta, validando también
     * el tiempo de expiración (5 minutos por defecto).
     *
     * Estados activos:
     * - "pagado": Siempre bloquea
     * - "pendiente_de_pago", "pendiente": Solo bloquea si NO ha expirado
     *
     * @return true si existe un pedido activo válido, false si no existe o ha expirado
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

            // Si está pagado, siempre bloquea
            if ("pagado".equals(estado)) {
                log.info("🔒 Receta bloqueada: pedido ya pagado");
                return true;
            }

            // Para otros estados, validar tiempo de expiración
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

            // Si no tiene fecha de creación, permitir (caso raro)
            log.warn("⚠️ Pedido sin fechaCreacion, permitiendo por defecto");
            return false;

        } catch (Exception e) {
            log.error("❌ Error verificando pedidos activos para receta {}", recetaId, e);
            // Fail-closed: en caso de error, bloquear por seguridad
            return true;
        }
    }

    // ==================================================================================
    // MÉTODOS DE CONSULTA Y ACTUALIZACIÓN
    // ==================================================================================

    public Map<String, Object> obtenerReceta(String recetaId) {
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
            log.info("✅ Receta {} obtenida correctamente", recetaId);
            return data;

        } catch (Exception e) {
            log.error("❌ Error obteniendo receta {}", recetaId, e);
            throw new RuntimeException("Error al obtener receta desde Firestore", e);
        }
    }

    public Map<String, Object> obtenerCotizacion(String recetaId, String cotizacionId) {
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
            log.info("✅ Cotización {} obtenida correctamente", cotizacionId);
            return data;

        } catch (Exception e) {
            log.error("❌ Error obteniendo cotización {}/{}", recetaId, cotizacionId, e);
            throw new RuntimeException("Error al obtener cotización desde Firestore", e);
        }
    }



    public List<String> buscarPedidosPendientesAntiguos(int minutes) {
        long cutoffSeconds = Instant.now()
                .minus(Duration.ofMinutes(minutes))
                .getEpochSecond();

        Timestamp cutoff = Timestamp.ofTimeSecondsAndNanos(cutoffSeconds, 0);

        try {
            return buscarConQuery(cutoff, minutes);
        } catch (Exception e) {
            log.warn("⚠️ Consulta compuesta falló, aplicando fallback: {}", e.getMessage());
            extraerURLIndice(e.getMessage());
            return buscarConFallback(cutoffSeconds, minutes);
        }
    }

    private List<String> buscarConQuery(Timestamp cutoff, int minutes) throws Exception {
        var query = db.collection("pedidos")
                .whereEqualTo("estado", "pendiente_de_pago")
                .whereLessThan("fechaCreacion", cutoff);

        var querySnapshot = query.get().get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

        List<String> ids = new ArrayList<>();
        querySnapshot.getDocuments().forEach(doc -> ids.add(doc.getId()));

        log.info("🔍 Encontrados {} pedidos pendientes de más de {} minutos", ids.size(), minutes);
        return ids;
    }

    private List<String> buscarConFallback(long cutoffSeconds, int minutes) {
        try {
            var query = db.collection("pedidos").whereEqualTo("estado", "pendiente_de_pago");
            var snapshot = query.get().get(firestoreTimeoutSeconds, TimeUnit.SECONDS);

            List<String> ids = new ArrayList<>();
            snapshot.getDocuments().forEach(doc -> {
                try {
                    Timestamp ts = doc.getTimestamp("fechaCreacion");
                    if (ts != null && ts.toDate().toInstant().getEpochSecond() < cutoffSeconds) {
                        ids.add(doc.getId());
                    } else if (ts == null) {
                        log.debug("Documento {} sin fechaCreacion, ignorado", doc.getId());
                    }
                } catch (Exception ex) {
                    log.warn("Error procesando doc {}: {}", doc.getId(), ex.getMessage());
                }
            });

            log.warn("⚠️ Fallback aplicado: {} pedidos encontrados (filtrado en cliente)", ids.size());
            return ids;
        } catch (Exception ex) {
            log.error("❌ Fallback también falló", ex);
            throw new RuntimeException("Error buscando pedidos pendientes antiguos", ex);
        }
    }

    private void extraerURLIndice(String errorMessage) {
        try {
            int idx = errorMessage.indexOf("https://console.firebase.google.com/");
            if (idx != -1) {
                int end = errorMessage.indexOf(' ', idx);
                String url = end == -1 ? errorMessage.substring(idx) : errorMessage.substring(idx, end);
                log.warn("💡 Crea el índice necesario aquí: {}", url);
            }
        } catch (Exception ignore) {
            // No crítico
        }
    }

    // ==================================================================================
    // MÉTODOS DE ACTUALIZACIÓN DE ESTADO
    // ==================================================================================

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