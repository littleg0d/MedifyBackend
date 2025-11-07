package com.medify.medicamentos_backend.dto;

public class PreferenciaResponse {
    private String paymentUrl;
    private String preferenceId;

    // Constructores
    public PreferenciaResponse() {}

    public PreferenciaResponse(String paymentUrl, String preferenceId) {
        this.paymentUrl = paymentUrl;
        this.preferenceId = preferenceId;
    }

    // Getters y Setters
    public String getPaymentUrl() {
        return paymentUrl;
    }

    public void setPaymentUrl(String paymentUrl) {
        this.paymentUrl = paymentUrl;
    }

    public String getPreferenceId() {
        return preferenceId;
    }

    public void setPreferenceId(String preferenceId) {
        this.preferenceId = preferenceId;
    }

    @Override
    public String toString() {
        return "PreferenciaResponse{" +
                "paymentUrl='" + paymentUrl + '\'' +
                ", preferenceId='" + preferenceId + '\'' +
                '}';
    }
}