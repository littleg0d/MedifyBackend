package com.tuapp.medicamentos.dto;

public class PreferenciaRequest {
    private String nombreComercial;
    private Double precio;
    private String recetaId;
    private String farmaciaId;
    private String farmaciaNombre;
    private String imagenUrl; // NUEVO - URL de imagen del producto
    private String descripcion; // NUEVO - Descripción del producto

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

    public String getFarmaciaId() {
        return farmaciaId;
    }

    public void setFarmaciaId(String farmaciaId) {
        this.farmaciaId = farmaciaId;
    }

    public String getFarmaciaNombre() {
        return farmaciaNombre;
    }

    public void setFarmaciaNombre(String farmaciaNombre) {
        this.farmaciaNombre = farmaciaNombre;
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