package com.logitrack.chatbot.security;

import java.time.Instant;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Flux;

/** Contexto exclusivo de cada petición; nunca usa una credencial de servicio o de administrador. */
public final class JwtRelayContext {
    public static final String KEY = JwtRelayContext.class.getName();
    private static final ThreadLocal<Jwt> CURRENT = new ThreadLocal<>();
    private JwtRelayContext() {}

    public static Jwt get() { return CURRENT.get(); }
    public static void set(Jwt jwt) { if (jwt == null) CURRENT.remove(); else CURRENT.set(jwt); }
    public static void clear() { CURRENT.remove(); }

    public static Jwt requireJwt() {
        Jwt jwt = get();
        if (jwt == null && SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken auth
                && auth.isAuthenticated()) {
            jwt = auth.getToken();
        }
        if (jwt == null || jwt.getExpiresAt() == null || !Instant.now().isBefore(jwt.getExpiresAt())) {
            throw new AuthenticationCredentialsNotFoundException("No hay un JWT autenticado vigente para la consulta");
        }
        return jwt;
    }

    /** Capturar durante la petición, antes de devolver el Flux; útil también fuera de Spring MVC. */
    public static <T> Flux<T> propagate(Flux<T> publisher) {
        Jwt jwt = requireJwt();
        return publisher.contextWrite(context -> context.put(KEY, jwt));
    }
}
