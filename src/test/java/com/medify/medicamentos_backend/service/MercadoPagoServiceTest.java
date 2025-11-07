package com.medify.medicamentos_backend.service;

import com.mercadopago.client.preference.PreferenceClient;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.net.MPResponse;
import com.mercadopago.resources.preference.Preference;
import com.medify.medicamentos_backend.dto.Address;
import com.medify.medicamentos_backend.dto.PreferenciaRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MercadoPagoServiceTest {

    // Helper to build a sample PreferenciaRequest
    private PreferenciaRequest sampleRequest() {
        PreferenciaRequest r = new PreferenciaRequest();
        r.setNombreComercial("Farmacia La Salud");
        r.setPrecio(1700.00);
        r.setRecetaId("xORTYX8WnkwGDgaD2oV");
        r.setUserId("user123");
        r.setFarmaciaId("farm_4");
        r.setCotizacionId("cot123");
        r.setImagenUrl("https://images.unsplash.com/158554357343-3b092031a891?w=400");
        r.setDescripcion("desc");
        Address a = new Address();
        a.setStreet("Calle 64 277");
        a.setCity("La Plata");
        a.setProvince("Buenos Aires");
        a.setPostalCode("1900");
        r.setDireccion(a);
        return r;
    }

    @Test
    void crearPreferencia_whenClientThrows_thenPropagatesMPApiException() throws Exception {
        // Arrange
        PreferenceClient mockClient = mock(PreferenceClient.class);
        MercadoPagoService svc = new MercadoPagoService() {
            @Override
            protected PreferenceClient createPreferenceClient() {
                return mockClient;
            }
        };

        PreferenciaRequest req = sampleRequest();
        String pedidoId = "pedido-1";

        // make client.create throw MPException (use MPException to avoid complex MPApiException construction)
        when(mockClient.create(any())).thenThrow(new MPException("Api error"));

        // Act & Assert
        MPException ex = assertThrows(MPException.class, () -> svc.crearPreferencia(req, pedidoId));
        assertTrue(ex.getMessage().contains("Api error"));

        // verify client.create was called
        verify(mockClient, times(1)).create(any());
    }

    @Test
    void crearPreferencia_whenClientReturns_thenReturnsPreference() throws Exception {
        // Arrange
        PreferenceClient mockClient = mock(PreferenceClient.class);
        MercadoPagoService svc = new MercadoPagoService() {
            @Override
            protected PreferenceClient createPreferenceClient() {
                return mockClient;
            }
        };

        PreferenciaRequest req = sampleRequest();
        String pedidoId = "pedido-2";

        Preference mockPref = mock(Preference.class);
        when(mockPref.getInitPoint()).thenReturn("https://mp.example/pay/123");
        when(mockClient.create(any())).thenReturn(mockPref);

        // Act
        Preference result = svc.crearPreferencia(req, pedidoId);

        // Assert
        assertNotNull(result);
        assertEquals("https://mp.example/pay/123", result.getInitPoint());
        verify(mockClient, times(1)).create(any());
    }
}
