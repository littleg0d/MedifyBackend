package com.medify.medicamentos_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    @Value("${cors.allowed-origins}")
    private String allowedOriginsStr;

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();

        // ✅ Si es "*", usar allowedOriginPatterns en vez de allowedOrigins
        if ("*".equals(allowedOriginsStr.trim())) {
            config.setAllowCredentials(true);
            config.addAllowedOriginPattern("*"); // Permite cualquier origen con credentials
        } else {
            config.setAllowCredentials(true);
            String[] origins = allowedOriginsStr.split(",");
            config.setAllowedOrigins(Arrays.asList(origins));
        }

        config.setAllowedHeaders(Arrays.asList("*"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setMaxAge(3600L);

        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}