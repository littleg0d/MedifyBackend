package com.medify.medicamentos_backend.service;

import com.google.api.core.ApiFuture;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import com.medify.medicamentos_backend.dto.PreferenciaRequest;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class FirebaseService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseService.class);
    private static final long FIRESTORE_TIMEOUT_SECONDS = 10;

    @Value("${firebase.service.account.path:}")
    private String serviceAccountPath;

    private volatile boolean firebaseInitialized = false;

    @PostConstruct
    public void init() {
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseOptions.Builder optionsBuilder = FirebaseOptions.builder();

                if (serviceAccountPath != null && !serviceAccountPath.isBlank()) {
                    log.info("Inicializando Firebase con service account desde: {}", serviceAccountPath);
                    try (FileInputStream serviceAccount = new FileInputStream(serviceAccountPath)) {
                        GoogleCredentials credentials = GoogleCredentials.fromStream(serviceAccount);
                        optionsBuilder.setCredentials(credentials);
                    }
                } else {
                    log.info("Inicializando Firebase con Application Default Credentials");
                    try {
                        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
                        optionsBuilder.setCredentials(credentials);
                    } catch (IOException e) {
                        log.warn("No se pudieron obtener credenciales: {}. Firebase no disponible.", e.getMessage());
                        return;
                    }
                }

                FirebaseApp.initializeApp(optionsBuilder.build());
                firebaseInitialized = true;
                log.info("Firebase inicializado correctamente");
            } else {
                firebaseInitialized = true;
                log.info("Firebase ya estaba inicializado");
            }
        } catch (Exception ex) {
            log.warn("Error inicializando Firebase: {}. Firebase no disponible.", ex.getMessage());
        }
    }

    private Firestore getFirestore() {
        if (!firebaseInitialized) {
            throw new IllegalStateException("Firebase no esta inicializado");
        }
        return FirestoreClient.getFirestore();
    }

    public String crearPedido(PreferenciaRequest req) {
        try {
            Firestore db = getFirestore();

            Map<String, Object> data = new HashMap<>();
            data.put("recetaId", req.getRecetaId());
            data.put("farmaciaId", req.getFarmaciaId());
            data.put("userId", req.getUserId());
            data.put("addressUser", req.getDireccion());
            data.put("precio", req.getPrecio());
            data.put("cotizacionId", req.getCotizacionId());
            data.put("nombreComercial", req.getNombreComercial());
            data.put("imagenUrl", req.getImagenUrl());
            // Estado inicial cuando se crea la orden para pago
            data.put("estado", "pendiente_de_pago");
            data.put("fechaCreacion", FieldValue.serverTimestamp());
            data.put("fechaPago", null);

            DocumentReference docRef = db.collection("pedidos").document();
            docRef.set(data).get(FIRESTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            log.info("Pedido creado con id: {}", docRef.getId());
            return docRef.getId();
        } catch (Exception e) {
            log.error("Error creando pedido en Firestore", e);
            throw new RuntimeException("Error al crear pedido en Firestore", e);
        }
    }

    /**
     * Busca pedidos con estado 'pendiente_de_pago' más antiguos que los minutos indicados.
     * Retorna los IDs de los documentos encontrados.
     */
    public java.util.List<String> buscarPedidosPendientesAntiguos(int minutes) {
        // Calcular cutoff usando minutos
        long cutoffSeconds = java.time.Instant.now()
                .minus(java.time.Duration.ofMinutes(minutes))
                .getEpochSecond();

        com.google.cloud.Timestamp cutoff = com.google.cloud.Timestamp.ofTimeSecondsAndNanos(cutoffSeconds, 0);

        try {
            Firestore db = getFirestore();

            var query = db.collection("pedidos")
                    .whereEqualTo("estado", "pendiente_de_pago")
                    .whereLessThan("fechaCreacion", cutoff);

            var querySnapshot = query.get().get(FIRESTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            java.util.List<String> ids = new java.util.ArrayList<>();
            querySnapshot.getDocuments().forEach(doc -> ids.add(doc.getId()));

            log.info("🔍 Encontrados {} pedidos pendientes de más de {} minutos", ids.size(), minutes);
            return ids;

        } catch (Exception e) {
            // Si la excepción indica que hace falta un índice compuesto, hacemos un fallback.
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            log.warn("Consulta compuesta falló buscando pedidos pendientes antiguos: {}", msg);

            // Intentar detectar URL sugerida para crear índice en el mensaje de error
            try {
                String text = msg;
                int idx = text.indexOf("https://console.firebase.google.com/");
                if (idx != -1) {
                    int end = text.indexOf(' ', idx);
                    String url = end == -1 ? text.substring(idx) : text.substring(idx, end);
                    log.warn("Firestore requiere un índice para esta consulta. Puedes crear el índice aquí: {}", url);
                }
            } catch (Exception ignore) {
                // no crítico
            }

            // Fallback: consultar por estado solamente y filtrar por fechaCreacion en el cliente.
            try {
                Firestore db2 = getFirestore();
                var q = db2.collection("pedidos").whereEqualTo("estado", "pendiente_de_pago");
                var snapshot = q.get().get(FIRESTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                java.util.List<String> ids = new java.util.ArrayList<>();
                snapshot.getDocuments().forEach(doc -> {
                    try {
                        com.google.cloud.Timestamp ts = doc.getTimestamp("fechaCreacion");
                        if (ts != null) {
                            if (ts.toDate().toInstant().getEpochSecond() < cutoffSeconds) {
                                ids.add(doc.getId());
                            }
                        } else {
                            log.debug("Documento {} no tiene fechaCreacion, se ignora en cleanup", doc.getId());
                        }
                    } catch (Exception ex) {
                        log.warn("Error procesando doc {} en fallback: {}", doc.getId(), ex.getMessage());
                    }
                });

                log.warn("Fallback aplicado: filtrado en cliente realizado. Se retornan {} pedidos.", ids.size());
                return ids;
            } catch (Exception ex2) {
                log.error("Fallback también falló buscando pedidos pendientes antiguos", ex2);
                throw new RuntimeException("Error buscando pedidos pendientes antiguos", ex2);
            }
        }
    }

    /**
     * Marca un pedido como abandonado (abandonada) y registra fecha de actualización.
     */
    public void marcarPedidoAbandonado(String pedidoId) {
        try {
            Firestore db = getFirestore();
            Map<String, Object> updates = new HashMap<>();
            updates.put("estado", "abandonada");
            updates.put("fechaCierre", FieldValue.serverTimestamp());
            db.collection("pedidos").document(pedidoId)
                    .update(updates)
                    .get(FIRESTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Pedido {} marcado como abandonado", pedidoId);
        } catch (Exception e) {
            log.error("Error marcando pedido {} como abandonado", pedidoId, e);
            throw new RuntimeException("Error marcando pedido como abandonado", e);
        }
    }

    public void borrarPedido(String pedidoId) {
        try {
            Firestore db = getFirestore();
            db.collection("pedidos").document(pedidoId)
                    .delete()
                    .get(FIRESTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Pedido {} eliminado", pedidoId);
        } catch (Exception e) {
            log.error("Error borrando pedido {}", pedidoId, e);
            throw new RuntimeException("Error al borrar pedido", e);
        }
    }

    public void actualizarPedido(String pedidoId, Map<String, Object> updates) {
        try {
            Firestore db = getFirestore();
            db.collection("pedidos").document(pedidoId)
                    .update(updates)
                    .get(FIRESTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Pedido {} actualizado", pedidoId);
        } catch (Exception e) {
            log.error("Error actualizando pedido {}", pedidoId, e);
            throw new RuntimeException("Error al actualizar pedido", e);
        }
    }

    public void marcarPedidoComoPagado(String pedidoId, String paymentId, String status) {
        try {
            Firestore db = getFirestore();

            Map<String, Object> updates = new HashMap<>();
            updates.put("estado", "pagado");
            updates.put("paymentId", paymentId);
            updates.put("paymentStatus", status);
            updates.put("fechaPago", FieldValue.serverTimestamp());

            db.collection("pedidos").document(pedidoId)
                    .update(updates)
                    .get(FIRESTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            log.info("Pedido {} marcado como pagado (paymentId: {})", pedidoId, paymentId);
        } catch (Exception e) {
            log.error("Error marcando pedido {} como pagado", pedidoId, e);
            throw new RuntimeException("Error al marcar pedido como pagado", e);
        }
    }

    /**
     * Marca un pedido como pagado de forma idempotente usando transacciones
     * @return true si se actualizó, false si ya estaba pagado
     */
    public boolean marcarPedidoComoPagadoIdempotente(String pedidoId, String paymentId, String status) {
        try {
            Firestore db = getFirestore();

            // Usar transacción para verificar y actualizar atómicamente
            Boolean updated = db.runTransaction(transaction -> {
                DocumentReference pedidoRef = db.collection("pedidos").document(pedidoId);
                DocumentSnapshot snapshot = transaction.get(pedidoRef).get(FIRESTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                if (!snapshot.exists()) {
                    log.warn("Pedido {} no existe", pedidoId);
                    return false;
                }

                String estadoActual = snapshot.getString("estado");
                String paymentIdActual = snapshot.getString("paymentId");

                // Si ya está pagado con el mismo paymentId, es idempotente
                if ("pagado".equals(estadoActual) && paymentId.equals(paymentIdActual)) {
                    log.info("Pedido {} ya estaba marcado como pagado con payment {}, operación idempotente",
                            pedidoId, paymentId);
                    return false;
                }

                // Si ya está pagado con otro paymentId, es un error
                if ("pagado".equals(estadoActual)) {
                    log.error("Pedido {} ya pagado con payment {}, intento duplicado con payment {}",
                            pedidoId, paymentIdActual, paymentId);
                    return false;
                }

                // Actualizar a pagado
                Map<String, Object> updates = new HashMap<>();
                updates.put("estado", "pagado");
                updates.put("paymentId", paymentId);
                updates.put("paymentStatus", status);
                updates.put("fechaPago", FieldValue.serverTimestamp());
                transaction.update(pedidoRef, updates);
                // FINALIZAR LA RECETA
                // recetaId para actualizar a finalizada.

                String recetaId = snapshot.getString("recetaId");
                // Solo si encontramos un recetaId en el pedido
                if (recetaId != null && !recetaId.isBlank()) {
                    DocumentReference recetaRef = db.collection("recetas").document(recetaId);
                    // Actualizamos el estado de la receta para que no se pueda volver a cotizar/pagar
                    transaction.update(recetaRef, "estado", "finalizada");
                }

                return true;

            }).get(FIRESTORE_TIMEOUT_SECONDS + 2, TimeUnit.SECONDS);

            if (Boolean.TRUE.equals(updated)) {
                log.info("Pedido {} marcado como pagado (paymentId: {})", pedidoId, paymentId);
            }

            return Boolean.TRUE.equals(updated);

        } catch (Exception e) {
            log.error("Error marcando pedido {} como pagado", pedidoId, e);
            throw new RuntimeException("Error al marcar pedido como pagado", e);
        }
    }
}