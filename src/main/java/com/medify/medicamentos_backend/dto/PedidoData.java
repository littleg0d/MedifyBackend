package com.medify.medicamentos_backend.dto;

import java.util.Map;

/**
 * DTO que contiene todos los datos necesarios para crear un pedido
 * Obtenidos desde Firebase (usuario, farmacia, cotización)
 */
public class PedidoData {

    // Datos del usuario
    private String userId;
    private String userName;
    private String userEmail;
    private String userDNI;
    private String userPhone;
    private Map<String, String> userAddress;  // ✅ Map (tiene estructura)
    private Map<String, String> userObraSocial;

    // Datos de la farmacia
    private String farmaciaId;
    private String nombreComercial;
    private String farmEmail;
    private String farmPhone;
    private String horario;
    private String farmAddress;  // ✅ CORREGIDO: String simple, NO Map

    // Datos de la cotización
    private String cotizacionId;
    private String descripcion;
    private Double precio;

    // Datos de la receta
    private String recetaId;
    private String imagenUrl;

    public PedidoData() {}

    // Getters y Setters
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public String getUserDNI() {
        return userDNI;
    }

    public void setUserDNI(String userDNI) {
        this.userDNI = userDNI;
    }

    public String getUserPhone() {
        return userPhone;
    }

    public void setUserPhone(String userPhone) {
        this.userPhone = userPhone;
    }

    public Map<String, String> getUserAddress() {
        return userAddress;
    }

    public void setUserAddress(Map<String, String> userAddress) {
        this.userAddress = userAddress;
    }

    public Map<String, String> getUserObraSocial() {
        return userObraSocial;
    }

    public void setUserObraSocial(Map<String, String> userObraSocial) {
        this.userObraSocial = userObraSocial;
    }

    public String getFarmaciaId() {
        return farmaciaId;
    }

    public void setFarmaciaId(String farmaciaId) {
        this.farmaciaId = farmaciaId;
    }

    public String getNombreComercial() {
        return nombreComercial;
    }

    public void setNombreComercial(String nombreComercial) {
        this.nombreComercial = nombreComercial;
    }

    public String getFarmEmail() {
        return farmEmail;
    }

    public void setFarmEmail(String farmEmail) {
        this.farmEmail = farmEmail;
    }

    public String getFarmPhone() {
        return farmPhone;
    }

    public void setFarmPhone(String farmPhone) {
        this.farmPhone = farmPhone;
    }

    public String getHorario() {
        return horario;
    }

    public void setHorario(String horario) {
        this.horario = horario;
    }

    // ✅ CORREGIDO: Ahora es String
    public String getFarmAddress() {
        return farmAddress;
    }

    public void setFarmAddress(String farmAddress) {
        this.farmAddress = farmAddress;
    }

    public String getCotizacionId() {
        return cotizacionId;
    }

    public void setCotizacionId(String cotizacionId) {
        this.cotizacionId = cotizacionId;
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

    public String getRecetaId() {
        return recetaId;
    }

    public void setRecetaId(String recetaId) {
        this.recetaId = recetaId;
    }

    public String getImagenUrl() {
        return imagenUrl;
    }

    public void setImagenUrl(String imagenUrl) {
        this.imagenUrl = imagenUrl;
    }
}