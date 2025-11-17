package com.medify.medicamentos_backend.controller;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserRecord;
import com.medify.medicamentos_backend.dto..FarmaciaRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/farmacias")
public class FarmaciaController {

    private final Firestore firestore;
    private final FirebaseAuth firebaseAuth;

    public FarmaciaController(Firestore firestore, FirebaseAuth firebaseAuth) {
        this.firestore = firestore;
        this.firebaseAuth = firebaseAuth;
    }

   @PostMapping("/crear")
    public ResponseEntity<?> crearFarmacia(@Valid @RequestBody FarmaciaRequest req) {
        try {
            // 1️⃣ Crear usuario en Firebase Auth
            UserRecord.CreateRequest createRequest = new UserRecord.CreateRequest()
                    .setEmail(req.getEmail().trim())
                    .setPassword(req.getPassword())
                    .setDisplayName(req.getNombreComercial().trim());

            UserRecord userRecord = firebaseAuth.createUser(createRequest);
            String uid = userRecord.getUid();

            // 2️⃣ Crear documento en Firestore /farmacias/{uid}
            Map<String, Object> data = new HashMap<>();
            data.put("nombreComercial", req.getNombreComercial().trim());
            data.put("email", req.getEmail().trim());
            data.put("direccion", req.getDireccion().trim());
            data.put("telefono", clean(req.getTelefono()));
            data.put("horario", clean(req.getHorario()));
            data.put("usuario", clean(req.getUsuario()));
            data.put("role", "farmacia");
            data.put("authUid", uid);
            data.put("createdAt", FieldValue.serverTimestamp());

            ApiFuture<DocumentReference> future =
                    firestore.collection("farmacias").add(data);
            future.get(); // esperar a que se escriba

            Map<String, Object> resp = new HashMap<>();
            resp.put("uid", uid);
            resp.put("message", "Farmacia creada correctamente");

            return ResponseEntity.status(HttpStatus.CREATED).body(resp);

        } catch (ExecutionException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno al crear la farmacia"));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private String clean(String value) {
        return (value == null || value.trim().isEmpty()) ? null : value.trim();
    }
}
