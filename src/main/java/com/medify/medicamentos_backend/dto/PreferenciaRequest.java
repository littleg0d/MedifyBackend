package com.medify.medicamentos_backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.URL;

public class PreferenciaRequest {

    @JsonAlias({"nombre"})
    @NotEmpty(message = "El nombre comercial es requerido")
    private String nombreComercial;

    @NotNull(message = "El precio es requerido")
    @Min(value = 1, message = "El precio debe ser mayor a 0")
    private Double precio;

    @NotEmpty(message = "El ID de receta es requerido")
    private String recetaId;

    @NotEmpty(message = "El userId es requerido")
    private String userId;

    private String farmaciaId;

    // Ahora la dirección es un objeto tipado para permitir validación
    @JsonAlias({"address", "direccion"})
    @Valid
    private Address direccion;
    private String cotizacionId;

    @URL(message = "La URL de la imagen no es válida")
    private String imagenUrl;

    private String descripcion;

    // Constructores
    public PreferenciaRequest() {}

    public PreferenciaRequest(String nombreComercial, Double precio, String recetaId) {
        this.nombreComercial = nombreComercial;
        this.precio = precio;
        this.recetaId = recetaId;
    }

    // Getters y Setters
    public String getNombreComercial() {
        return nombreComercial;
    }

    public void setNombreComercial(String nombreComercial) {
        this.nombreComercial = nombreComercial;
    }

    public Double getPrecio() {
        return precio;
    }

    public void setPrecio(Double precio) {
        this.precio = precio;
    }

    public String getRecetaId() {
        return recetaId;
    }

    public void setRecetaId(String recetaId) {
        this.recetaId = recetaId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getFarmaciaId() {
        return farmaciaId;
    }

    public void setFarmaciaId(String farmaciaId) {
        this.farmaciaId = farmaciaId;
    }

    // Dirección como objeto validado
    public Address getDireccion() {
        return direccion;
    }

    public void setDireccion(Address direccion) {
        this.direccion = direccion;
    }

    public String getCotizacionId() {
        return cotizacionId;
    }

    public void setCotizacionId(String cotizacionId) {
        this.cotizacionId = cotizacionId;
    }

    public String getImagenUrl() {
        return imagenUrl;
    }

    public void setImagenUrl(String imagenUrl) {
        this.imagenUrl = imagenUrl;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }
}