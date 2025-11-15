package com.medify.medicamentos_backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request simplificado para crear preferencia de pago
 * Solo recibe IDs, los datos se obtienen desde Firebase
 */
public class PreferenciaRequest {

    @NotBlank(message = "El farmaciaId es requerido")
    private String farmaciaId;

    @NotBlank(message = "El userId es requerido")
    private String userId;

    @NotBlank(message = "El cotizacionId es requerido")
    private String cotizacionId;

    @NotBlank(message = "El recetaId es requerido")
    private String recetaId;

    // Constructores
    public PreferenciaRequest() {}

    public PreferenciaRequest(String farmaciaId, String userId, String cotizacionId, String recetaId) {
        this.farmaciaId = farmaciaId;
        this.userId = userId;
        this.cotizacionId = cotizacionId;
        this.recetaId = recetaId;
    }

    // Getters y Setters
    public String getFarmaciaId() {
        return farmaciaId;
    }

    public void setFarmaciaId(String farmaciaId) {
        this.farmaciaId = farmaciaId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCotizacionId() {
        return cotizacionId;
    }

    public void setCotizacionId(String cotizacionId) {
        this.cotizacionId = cotizacionId;
    }

    public String getRecetaId() {
        return recetaId;
    }

    public void setRecetaId(String recetaId) {
        this.recetaId = recetaId;
    }

    @Override
    public String toString() {
        return "PreferenciaRequest{" +
                "farmaciaId='" + farmaciaId + '\'' +
                ", userId='" + userId + '\'' +
                ", cotizacionId='" + cotizacionId + '\'' +
                ", recetaId='" + recetaId + '\'' +
                '}';
    }
}