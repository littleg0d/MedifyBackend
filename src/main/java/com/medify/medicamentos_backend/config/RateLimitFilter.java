package com.medify.medicamentos_backend.config;

import com.medify.medicamentos_backend.service.RateLimitService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Filtro global de rate limiting para todas las peticiones HTTP
 */
@Component
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitService rateLimitService;

    public RateLimitFilter(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();

        // Aplicar rate limit solo a endpoints sensibles
        if (shouldRateLimit(path)) {
            String identifier = getClientIdentifier(httpRequest);
            String namespace = getNamespace(path);

            if (!rateLimitService.allowRequest(identifier, namespace)) {
                log.warn("Rate limit excedido para {} en {}", identifier, path);
                httpResponse.setStatus(429); // Too Many Requests
                httpResponse.setContentType("application/json");
                httpResponse.getWriter().write("{\"error\":\"Rate limit excedido. Intente más tarde.\"}");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * Determina si el path debe tener rate limiting
     */
    private boolean shouldRateLimit(String path) {
        // Excluir explícitamente el webhook para evitar 429 en notificaciones entrantes
        if (path.contains("/webhook")) {
            return false;
        }

        return path.startsWith("/api/pagos/") ||
                path.startsWith("/api/imagenes/subir");
    }

    /**
     * Obtiene el identificador del cliente (IP o header personalizado)
     */
    private String getClientIdentifier(HttpServletRequest request) {
        // Priorizar header X-Forwarded-For si está detrás de proxy
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }

        // Alternativa: X-Real-IP
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }

        // Fallback a IP directa
        return request.getRemoteAddr();
    }

    /**
     * Determina el namespace según el path
     */
    private String getNamespace(String path) {
        if (path.contains("/webhook")) {
            return "webhook";
        } else if (path.contains("/crear-preferencia")) {
            return "payment";
        } else if (path.contains("/imagenes")) {
            return "image";
        }
        return "api";
    }
}