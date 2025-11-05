package com.tuapp.medicamentos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MedicamentosBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MedicamentosBackendApplication.class, args);
        System.out.println("\n=================================");
        System.out.println("✅ Servidor iniciado correctamente");
        System.out.println("🔗 http://localhost:8080/api/pagos/health");
        System.out.println("=================================\n");
    }
}