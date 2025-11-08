package com.medify.medicamentos_backend.service;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import com.google.firebase.cloud.FirestoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Servicio para prevenir procesamiento duplicado de webhooks usando locks distribuidos en Firestore
 */
@Service
public class WebhookLockService {

    private static final Logger log = LoggerFactory.getLogger(WebhookLockService.class);
    private static final long LOCK_TIMEOUT_SECONDS = 300; // 5 minutos
    private static final long TRANSACTION_TIMEOUT_SECONDS = 10;

    /**
     * Intenta adquirir un lock para procesar un webhook
     * @param paymentId ID del pago a procesar
     * @return true si se adquirió el lock, false si ya está siendo procesado
     */
    public boolean tryAcquireLock(String paymentId) {
        try {
            Firestore db = FirestoreClient.getFirestore();
            String lockId = "payment_lock_" + paymentId;

            // Usar transacción para garantizar atomicidad
            Boolean acquired = db.runTransaction(transaction -> {
                var lockRef = db.collection("webhook_locks").document(lockId);
                var snapshot = transaction.get(lockRef).get(TRANSACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                if (snapshot.exists()) {
                    // Verificar si el lock ha expirado
                    Long lockTimestamp = snapshot.getLong("timestamp");
                    if (lockTimestamp != null) {
                        long now = Instant.now().getEpochSecond();
                        if (now - lockTimestamp < LOCK_TIMEOUT_SECONDS) {
                            log.warn("Lock activo para payment {}, ignorando webhook duplicado", paymentId);
                            return false; // Lock activo, rechazar
                        }
                        log.info("Lock expirado para payment {}, renovando", paymentId);
                    }
                }

                // Adquirir o renovar lock
                Map<String, Object> lockData = new HashMap<>();
                lockData.put("paymentId", paymentId);
                lockData.put("timestamp", Instant.now().getEpochSecond());
                lockData.put("processId", Thread.currentThread().getName());

                transaction.set(lockRef, lockData);
                return true;
            }).get(TRANSACTION_TIMEOUT_SECONDS + 2, TimeUnit.SECONDS);

            return Boolean.TRUE.equals(acquired);

        } catch (Exception e) {
            log.error("Error adquiriendo lock para payment {}: {}", paymentId, e.getMessage(), e);
            // En caso de error, permitir procesamiento (fail-open)
            return true;
        }
    }

    /**
     * Libera el lock después de procesar exitosamente
     */
    public void releaseLock(String paymentId) {
        try {
            Firestore db = FirestoreClient.getFirestore();
            String lockId = "payment_lock_" + paymentId;

            db.collection("webhook_locks")
                    .document(lockId)
                    .delete()
                    .get(TRANSACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            log.debug("Lock liberado para payment {}", paymentId);
        } catch (Exception e) {
            log.error("Error liberando lock para payment {}: {}", paymentId, e.getMessage());
            // No es crítico si falla, el lock expirará automáticamente
        }
    }

    /**
     * Limpia locks expirados (ejecutar periódicamente)
     */
    public void cleanupExpiredLocks() {
        try {
            Firestore db = FirestoreClient.getFirestore();
            long cutoffTime = Instant.now().getEpochSecond() - LOCK_TIMEOUT_SECONDS;

            db.collection("webhook_locks")
                    .whereLessThan("timestamp", cutoffTime)
                    .get()
                    .get(TRANSACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .getDocuments()
                    .forEach(doc -> {
                        try {
                            doc.getReference().delete();
                            log.debug("Lock expirado eliminado: {}", doc.getId());
                        } catch (Exception e) {
                            log.warn("Error eliminando lock expirado {}: {}", doc.getId(), e.getMessage());
                        }
                    });

            log.info("Limpieza de locks expirados completada");
        } catch (Exception e) {
            log.error("Error limpiando locks expirados: {}", e.getMessage(), e);
        }
    }
}