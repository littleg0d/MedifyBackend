package com.medify.medicamentos_backend.config;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Maneja los errores de validación de @Valid (ej. @NotEmpty, @Min)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage())
        );
        log.warn("Error de validación: {}", errors);
        return new ResponseEntity<>(Map.of("errors", errors), HttpStatus.BAD_REQUEST);
    }

    /**
     * Maneja errores  de la API de Mercado Pago.
     */
    @ExceptionHandler({MPException.class, MPApiException.class})
    public ResponseEntity<?> handleMercadoPagoException(Exception ex) {
        log.error("Error al comunicarse con Mercado Pago: {}", ex.getMessage());
        // Error genérico.
        return new ResponseEntity<>(
                Map.of("error", "Error al procesar el pago con el proveedor."),
                HttpStatus.SERVICE_UNAVAILABLE // 503 - Servicio no disponible
        );
    }

    /**
     * Maneja cualquier otro error inesperado (Runtime).
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntimeException(RuntimeException ex) {
        log.error("Error interno inesperado: {}", ex.getMessage(), ex);
        return new ResponseEntity<>(
                Map.of("error", "Ha ocurrido un error interno en el servidor."),
                HttpStatus.INTERNAL_SERVER_ERROR // 500
        );
    }
}