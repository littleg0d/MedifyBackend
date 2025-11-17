package com.medify.medicamentos_backend.controller;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private final FirebaseAuth firebaseAuth;

    public AdminController(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    /**
     * Endpoint para eliminar un usuario de Firebase Authentication.
     * Esto es llamado por el frontend (usuarios.tsx y farmacias.tsx).
     */
    @DeleteMapping("/users/{uid}")
    public ResponseEntity<?> eliminarUsuarioAuth(@PathVariable String uid) {
        
        log.info("Solicitud para eliminar auth del UID: {}", uid);

        try {
            // Borra el usuario de Firebase Authentication
            firebaseAuth.deleteUser(uid);
            
            log.info("✅ Auth UID: {} eliminado correctamente.", uid);
            return ResponseEntity.ok(Map.of("message", "Usuario de Auth eliminado"));

        } catch (FirebaseAuthException e) {
            log.error("Error al eliminar auth UID: {}: {}", uid, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al eliminar usuario de Firebase Auth: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Error inesperado al eliminar UID: {}: {}", uid, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno del servidor."));
        }
    }
}
