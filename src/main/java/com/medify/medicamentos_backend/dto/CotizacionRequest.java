package com.medify.medicamentos_backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO para recibir la respuesta de una farmacia a una receta
 */
public class CotizacionRequest {

    @NotBlank(message = "El recetaId es requerido")
    private String recetaId;

    @NotBlank(message = "El farmaciaId es requerido")
    private String farmaciaId;

    @NotBlank(message = "El estado es requerido")
    private String estado;

    // Campos opcionales (solo requeridos si estado = "cotizado")
    private String descripcion;
    private Double precio;

    // Constructores
    public CotizacionRequest() {}

    public CotizacionRequest(String recetaId, String farmaciaId, String estado,
                             String descripcion, Double precio) {
        this.recetaId = recetaId;
        this.farmaciaId = farmaciaId;
        this.estado = estado;
        this.descripcion = descripcion;
        this.precio = precio;
    }

    // Getters y Setters
    public String getRecetaId() {
        return recetaId;
    }

    public void setRecetaId(String recetaId) {
        this.recetaId = recetaId;
    }

    public String getFarmaciaId() {
        return farmaciaId;
    }

    public void setFarmaciaId(String farmaciaId) {
        this.farmaciaId = farmaciaId;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public Double getPrecio() {
        return precio;
    }

    public void setPrecio(Double precio) {
        this.precio = precio;
    }

    @Override
    public String toString() {
        return "CotizacionRequest{" +
                "recetaId='" + recetaId + '\'' +
                ", farmaciaId='" + farmaciaId + '\'' +
                ", estado='" + estado + '\'' +
                ", descripcion='" + descripcion + '\'' +
                ", precio=" + precio +
                '}';
    }
}