package com.medify.medicamentos_backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.URL;

public class PreferenciaRequest {

    @NotBlank(message = "El nombre comercial es requerido")
    private String nombreComercial;



    @NotBlank(message = "El ID de receta es requerido")
    private String recetaId;

    @NotBlank(message = "El userId es requerido")
    private String userId;

    @NotBlank(message = "El farmaciaId es requerido")
    private String farmaciaId;

    @NotNull(message = "La dirección es requerida")
    @Valid
    @JsonAlias({"address", "direccion", "addressUser"})
    private Address direccion;

    @NotBlank(message = "El cotizacionId es requerido")
    private String cotizacionId;

    @NotBlank(message = "La imagen URL es requerida")
    @JsonAlias({"imagenurl", "imagenUrl", "imageUrl"})
    @URL(message = "La URL de la imagen no es válida")
    private String imagenUrl;

    private String descripcion; // ÚNICO CAMPO OPCIONAL

    // Constructores
    public PreferenciaRequest() {}

    public PreferenciaRequest(String nombreComercial, Double precio, String recetaId) {
        this.nombreComercial = nombreComercial;
        this.recetaId = recetaId;
    }

    // Getters y Setters
    public String getNombreComercial() {
        return nombreComercial;
    }

    public void setNombreComercial(String nombreComercial) {
        this.nombreComercial = nombreComercial;
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