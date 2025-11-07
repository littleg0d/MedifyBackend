package com.medify.medicamentos_backend.service;

import com.google.api.core.ApiFuture;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteResult;
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

    @Value("${firebase.service.account.path:}")
    private String serviceAccountPath;

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
                    log.info("Intentando inicializar Firebase con Application Default Credentials (GOOGLE_APPLICATION_CREDENTIALS o entorno GCP)");
                    try {
                        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
                        optionsBuilder.setCredentials(credentials);
                    } catch (IOException e) {
                        // No forzamos el fallo de arranque; solo informamos y dejamos que la app arranque.
                        log.warn("No se pudieron obtener las Application Default Credentials: {}. Firebase no se inicializará. Si necesita Firestore, configure 'firebase.service.account.path' o 'GOOGLE_APPLICATION_CREDENTIALS'.", e.getMessage());
                        return;
                    }
                }

                FirebaseOptions options = optionsBuilder.build();
                FirebaseApp.initializeApp(options);
                log.info("Firebase inicializado correctamente");
            } else {
                log.info("Firebase ya estaba inicializado");
            }
        } catch (Exception ex) {
            // Capturamos cualquier excepción para evitar que el contexto de Spring falle al iniciar.
            log.warn("Excepción durante la inicialización de Firebase: {}. Firebase no estará disponible.", ex.getMessage());
        }
    }

    /**
     * Crea un documento en la colección 'pedidos' con estado 'pendiente' y devuelve el pedidoId generado.
     */
    public String crearPedido(PreferenciaRequest req) throws Exception {
        Firestore db = FirestoreClient.getFirestore();

        Map<String, Object> data = new HashMap<>();
        data.put("recetaId", req.getRecetaId());
        data.put("farmaciaId", req.getFarmaciaId());
        data.put("userId", req.getUserId());
        // Guardar la dirección completa tal cual viene del frontend, bajo la clave 'addressUser' (solicitado)
        data.put("addressUser", req.getDireccion());
        data.put("precio", req.getPrecio());
        data.put("cotizacionId", req.getCotizacionId());
        // Guardar nombre comercial e imagen si vienen
        data.put("nombreComercial", req.getNombreComercial());
        data.put("imagenUrl", req.getImagenUrl());

        data.put("estado", "pendiente");
        data.put("fechacreacion", FieldValue.serverTimestamp());
        data.put("fechaPago", null);

        DocumentReference docRef = db.collection("pedidos").document();
        ApiFuture<WriteResult> writeResult = docRef.set(data);
        // Esperar a que la escritura termine (timeout razonable)
        writeResult.get(10, TimeUnit.SECONDS);

        log.info("Pedido creado en Firestore con id: {}", docRef.getId());
        return docRef.getId();
    }

    /**
     * Borra un pedido dado su ID (útil cuando la creación del pago falla y queremos limpiar)
     */
    public void borrarPedido(String pedidoId) throws Exception {
        Firestore db = FirestoreClient.getFirestore();
        DocumentReference docRef = db.collection("pedidos").document(pedidoId);
        ApiFuture<WriteResult> writeResult = docRef.delete();
        // El método delete en Firestore devuelve ApiFuture<WriteResult> en la versión usada
        writeResult.get(10, TimeUnit.SECONDS);
        log.info("Pedido {} eliminado de Firestore", pedidoId);
    }

    /**
     * Marca un pedido como pagado y añade información del pago.
     */
    public void marcarPedidoComoPagado(String pedidoId, String paymentId, String paymentStatus) throws Exception {
        Firestore db = FirestoreClient.getFirestore();
        DocumentReference docRef = db.collection("pedidos").document(pedidoId);

        Map<String, Object> updates = new HashMap<>();
        updates.put("estado", "pagado");
        updates.put("fechaPago", FieldValue.serverTimestamp());
        updates.put("paymentId", paymentId);
        updates.put("paymentStatus", paymentStatus);

        ApiFuture<WriteResult> writeResult = docRef.update(updates);
        writeResult.get(10, TimeUnit.SECONDS);

        log.info("Pedido {} marcado como pagado (paymentId={}, status={})", pedidoId, paymentId, paymentStatus);
    }

    /**
     * Actualiza el estado del pedido (uso general, p.ej. "error").
     */
    public void actualizarEstadoPedido(String pedidoId, String estado) throws Exception {
        Firestore db = FirestoreClient.getFirestore();
        DocumentReference docRef = db.collection("pedidos").document(pedidoId);

        Map<String, Object> updates = new HashMap<>();
        updates.put("estado", estado);
        if ("error".equalsIgnoreCase(estado)) {
            updates.put("fechaError", FieldValue.serverTimestamp());
        }

        ApiFuture<WriteResult> writeResult = docRef.update(updates);
        writeResult.get(10, TimeUnit.SECONDS);

        log.info("Pedido {} actualizado estado -> {}", pedidoId, estado);
    }
}
