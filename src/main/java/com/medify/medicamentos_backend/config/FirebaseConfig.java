package com.medify.medicamentos_backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${firebase.service.account.path:}")
    private String serviceAccountPath;

    /**
     * Bean principal de Firebase con manejo robusto de credenciales.
     * Soporta tanto archivo de servicio como Application Default Credentials.
     */
    @Bean
    public FirebaseApp firebaseApp() throws IOException {
        // Evitar múltiples inicializaciones
        if (!FirebaseApp.getApps().isEmpty()) {
            log.info("Firebase ya estaba inicializado");
            return FirebaseApp.getInstance();
        }

        FirebaseOptions.Builder optionsBuilder = FirebaseOptions.builder();

        // Intentar primero con service account si está configurado
        if (serviceAccountPath != null && !serviceAccountPath.isBlank()) {
            log.info("Inicializando Firebase con service account desde: {}", serviceAccountPath);
            try (FileInputStream serviceAccount = new FileInputStream(serviceAccountPath)) {
                GoogleCredentials credentials = GoogleCredentials.fromStream(serviceAccount);
                optionsBuilder.setCredentials(credentials);
            } catch (IOException e) {
                log.warn("No se pudo leer service account: {}. Intentando con ADC...", e.getMessage());
                optionsBuilder.setCredentials(GoogleCredentials.getApplicationDefault());
            }
        } else {
            // Usar Application Default Credentials
            log.info("Inicializando Firebase con Application Default Credentials");
            optionsBuilder.setCredentials(GoogleCredentials.getApplicationDefault());
        }

        FirebaseApp app = FirebaseApp.initializeApp(optionsBuilder.build());
        log.info("✅ Firebase inicializado correctamente");
        return app;
    }

    /**
     * Bean de Firestore que depende del FirebaseApp.
     * Spring garantiza que firebaseApp() se ejecute primero.
     */
    @Bean
    public Firestore firestore(FirebaseApp firebaseApp) {
        Firestore db = FirestoreClient.getFirestore(firebaseApp);
        log.info("✅ Firestore cliente inicializado");
        return db;
    }
}
