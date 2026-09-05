package com.pulse_gym.ms_gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.pulse_gym.lb_common.services.JwtService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
@Component
@Slf4j
public class JwtValidationFilter implements GlobalFilter, Ordered {

    /** Servicio de Jwt */
    private final JwtService jwtService;

    /** Lista de rutas internas permitidas para acceso biométrico */
    private static final List<String> ALLOWED_INTERNAL_PATHS = Arrays.asList(
            "/api/internal/socios-membresias/biometrico");

    /**
     * Verifica si la ruta corresponde a una API interna
     * 
     * @param path Ruta a verificar
     * @return true si es una ruta interna, false si es una ruta permitida
     */
    private boolean isInternalPath(String path) {
        for (String allowedPath : ALLOWED_INTERNAL_PATHS) {
            if (path.contains(allowedPath)) {
                return false;
            }
        }
        return path.contains("/api/internal/");
    }

    /**
     * Retorna una respuesta de error 403 (Prohibido)
     * 
     * @param exchange Intercambio HTTP que contiene la respuesta
     * @param message  Mensaje de error que se envía al cliente
     * @return Mono con la respuesta de error en formato JSON
     */
    private Mono<Void> forbidden(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(("{\"error\": \"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /** Valida el JWT y agrega los datos del usuario a los headers de la petición */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (isInternalPath(path)) {
            return forbidden(exchange, "Acceso denegado a rutas internas");
        }

        if (isPublicPath(path) || exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }

        List<String> authHeaders = exchange.getRequest().getHeaders().getOrEmpty(HttpHeaders.AUTHORIZATION);
        if (authHeaders.isEmpty() || !authHeaders.get(0).startsWith("Bearer ")) {
            return unauthorized(exchange, "Header Authorization missing or invalid");
        }

        String token = authHeaders.get(0).substring(7);
        if (!jwtService.isTokenValid(token)) {
            return unauthorized(exchange, "Token invalido o expirado");
        }

        Long userId = jwtService.extractUserId(token);
        String rol = jwtService.extractRol(token);
        String email = jwtService.extractEmail(token);
        String username = jwtService.extractUsername(token);

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-User-Id", userId != null ? userId.toString() : "")
                .header("X-User-Name", username != null ? username : "")
                .header("X-User-Rol", rol != null ? rol : "")
                .header("X-User-Email", email != null ? email : "")
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        return chain.filter(mutatedExchange);
    }

    /**
     * Verifica si la ruta solicitada es pública (no requiere autenticación)
     * 
     * @param path Ruta de la petición HTTP
     * @return true si la ruta es pública, false si requiere autenticación
     */
    private boolean isPublicPath(String path) {
        return path.startsWith("/pg-ms-auth/auth/login")
                || path.startsWith("/pg-ms-auth/auth/register")
                || path.startsWith("/pg-ms-auth/auth/refresh")
                || path.startsWith("/pg-ms-auth/auth/forgot-password")
                || path.startsWith("/pg-ms-auth/auth/reset-password")
                || path.startsWith("/pg-ms-operation/api/asistencias/entrada-biometrica")
                || path.startsWith("/pg-ms-operation/api/asistencias/entrada-biometrica")
                || path.contains("/api/v1/pagos/comprobante/")
                || path.startsWith("/pg-ms-auth/auth/biometric/login");
    }

    /**
     * Retorna una respuesta de error 401 (No autorizado)
     * 
     * @param exchange Intercambio HTTP que contiene la respuesta
     * @param message  Mensaje de error que se envía al cliente
     * @return Mono con la respuesta de error en formato JSON
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(("{\"error\": \"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /** Prioridad más alta para ejecutar el filtro primero */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

}