package com.medify.medicamentos_backend.config;
import com.google.firebase.auth.FirebaseAuth;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
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
    
    @Value("${firebase.connect.timeout:60000}")
    private int connectTimeout;
    
    @Value("${firebase.read.timeout:60000}")
    private int readTimeout;
    
    /**
     * Bean principal de Firebase con manejo robusto de credenciales y timeouts.
     * Soporta tanto archivo de servicio como Application Default Credentials.
     */
    @Bean
    public FirebaseApp firebaseApp() throws IOException {
        // Evitar múltiples inicializaciones
        if (!FirebaseApp.getApps().isEmpty()) {
            log.info("🔄 Firebase ya estaba inicializado");
            return FirebaseApp.getInstance();
        }
        
        log.info("🚀 Iniciando configuración de Firebase...");
        log.info("📊 Timeouts configurados - Connect: {}ms, Read: {}ms", connectTimeout, readTimeout);
        
        GoogleCredentials credentials = obtenerCredenciales();
        
        FirebaseOptions options = FirebaseOptions.builder()
            .setCredentials(credentials)
            .setConnectTimeout(connectTimeout)
            .setReadTimeout(readTimeout)
            .build();
        
        FirebaseApp app = FirebaseApp.initializeApp(options);
        log.info("✅ Firebase inicializado correctamente");
        
        return app;
    }
    
    /**
     * Obtiene las credenciales de Firebase con fallback robusto.
     */
    private GoogleCredentials obtenerCredenciales() throws IOException {
        // Intentar primero con service account si está configurado
        if (serviceAccountPath != null && !serviceAccountPath.isBlank()) {
            log.info("🔐 Intentando autenticación con service account: {}", serviceAccountPath);
            try (FileInputStream serviceAccount = new FileInputStream(serviceAccountPath)) {
                GoogleCredentials credentials = GoogleCredentials.fromStream(serviceAccount);
                log.info("✅ Credenciales cargadas desde service account");
                return credentials;
            } catch (IOException e) {
                log.warn("⚠️ No se pudo leer service account: {}. Intentando con ADC...", e.getMessage());
            }
        }
        
        // Fallback a Application Default Credentials
        log.info("🔐 Intentando autenticación con Application Default Credentials");
        try {
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
            log.info("✅ Credenciales cargadas desde ADC");
            return credentials;
        } catch (IOException e) {
            log.error("❌ Error obteniendo Application Default Credentials", e);
            throw new IOException("No se pudieron obtener credenciales de Firebase. Verifica GOOGLE_APPLICATION_CREDENTIALS", e);
        }
    }
    
    /**
     * Bean de Firestore directamente desde FirebaseApp.
     * Usa los timeouts configurados en FirebaseApp.
     */
    @Bean
    public Firestore firestore(FirebaseApp firebaseApp) {
        log.info("🔧 Configurando cliente de Firestore...");
        
        try {
            Firestore db = FirestoreClient.getFirestore(firebaseApp);
            
            // Verificar conectividad básica
            verificarConectividad(db);
            
            log.info("✅ Firestore cliente inicializado correctamente");
            return db;
            
        } catch (Exception e) {
            log.error("❌ Error inicializando Firestore: {}", e.getMessage(), e);
            throw new RuntimeException("Error al inicializar Firestore", e);
        }
    }
    /**
 * Bean de FirebaseAuth para manejar la autenticación.
 */
@Bean
public FirebaseAuth firebaseAuth(FirebaseApp firebaseApp) {
    log.info("🔧 Configurando cliente de FirebaseAuth...");
    try {
        FirebaseAuth auth = FirebaseAuth.getInstance(firebaseApp);
        log.info("✅ FirebaseAuth cliente inicializado correctamente");
        return auth;
    } catch (Exception e) {
        log.error("❌ Error inicializando FirebaseAuth: {}", e.getMessage(), e);
        throw new RuntimeException("Error al inicializar FirebaseAuth", e);
    }
}   
    /**
     * Verifica la conectividad con Firestore de manera no bloqueante.
     */
    private void verificarConectividad(Firestore db) {
        try {
            log.info("🔍 Verificando conectividad con Firestore...");
            
            // Intenta listar colecciones (operación ligera)
            Iterable<com.google.cloud.firestore.CollectionReference> collections = 
                db.listCollections();
            
            // Solo iteramos para verificar que funcione, no procesamos
            if (collections.iterator().hasNext()) {
                log.info("✅ Conectividad con Firestore verificada");
            } else {
                log.info("✅ Conectividad OK (sin colecciones aún)");
            }
            
        } catch (Exception e) {
            log.warn("⚠️ No se pudo verificar conectividad con Firestore: {}", e.getMessage());
            // No lanzamos excepción para no interrumpir el inicio
        }
    }
}
