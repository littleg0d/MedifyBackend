package com.medify.medicamentos_backend.dto;

import jakarta.validation.constraints.NotEmpty;
@Deprecated
public class Address {

    @NotEmpty(message = "street es requerido")
    private String street;

    @NotEmpty(message = "city es requerido")
    private String city;

    @NotEmpty(message = "province es requerido")
    private String province;

    @NotEmpty(message = "postalCode es requerido")
    private String postalCode;

    public Address() {}

    public Address(String street, String city, String province, String postalCode) {
        this.street = street;
        this.city = city;
        this.province = province;
        this.postalCode = postalCode;
    }

    public String getStreet() {
        return street;
    }

    public void setStreet(String street) {
        this.street = street;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }
}