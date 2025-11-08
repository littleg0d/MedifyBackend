package com.medify.medicamentos_backend.controller;

import com.mercadopago.resources.preference.Preference;
import com.medify.medicamentos_backend.exception.GlobalExceptionHandler;
import com.medify.medicamentos_backend.service.FirebaseService;
import com.medify.medicamentos_backend.service.MercadoPagoService;
import com.medify.medicamentos_backend.service.PagoProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PagoControllerTest {

    private MockMvc mockMvc;

    @Mock
    private MercadoPagoService mercadoPagoService;

    @Mock
    private FirebaseService firebaseService;

    @Mock
    private PagoProcessingService pagoProcessingService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        PagoController controller = new PagoController(mercadoPagoService, firebaseService, pagoProcessingService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void crearPreferencia_withValidRequest_returns200AndPaymentUrl() throws Exception {
        String json = "{\n" +
                "  \"nombreComercial\": \"Ibuprofeno 600mg\",\n" +
                "  \"precio\": 1500.5,\n" +
                "  \"recetaId\": \"receta_123\",\n" +
                "  \"userId\": \"user_001\",\n" +
                "  \"farmaciaId\": \"farmacia_001\",\n" +
                "  \"direccion\": {\n" +
                "    \"street\": \"Av. Rivadavia 1234\",\n" +
                "    \"city\": \"Buenos Aires\",\n" +
                "    \"province\": \"CABA\",\n" +
                "    \"postalCode\": \"1406\"\n" +
                "  }\n" +
                "}";

        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(firebaseService.crearPedido(any())).thenReturn("pedido-abc");

        Preference mockPref = org.mockito.Mockito.mock(Preference.class);
        when(mockPref.getInitPoint()).thenReturn("https://mp.qa/pay/123");
        when(mockPref.getId()).thenReturn("pref-123");

        when(mercadoPagoService.crearPreferencia(any(), any())).thenReturn(mockPref);

        mockMvc.perform(post("/api/pagos/crear-preferencia")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentUrl").value("https://mp.qa/pay/123"))
                .andExpect(jsonPath("$.preferenceId").value("pref-123"));
    }

    @Test
    void crearPreferencia_withMissingNombreComercial_returns400() throws Exception {
        String json = "{\n" +
                "  \"precio\": 1500.5,\n" +
                "  \"recetaId\": \"receta_123\",\n" +
                "  \"userId\": \"user_001\",\n" +
                "  \"farmaciaId\": \"farmacia_001\",\n" +
                "  \"direccion\": {\n" +
                "    \"street\": \"Av. Rivadavia 1234\",\n" +
                "    \"city\": \"Buenos Aires\",\n" +
                "    \"province\": \"CABA\",\n" +
                "    \"postalCode\": \"1406\"\n" +
                "  }\n" +
                "}";

        mockMvc.perform(post("/api/pagos/crear-preferencia")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.nombreComercial").exists());
    }
}

