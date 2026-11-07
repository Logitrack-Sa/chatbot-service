package com.logitrack.chatbot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Ejecutado después de validar el Bearer; el finally evita contaminar la próxima petición. */
public class SecurityContextPropagationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var previous = JwtRelayContext.get();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        JwtRelayContext.set(authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()
                ? jwt.getToken() : null);
        try {
            chain.doFilter(request, response);
        } finally {
            JwtRelayContext.set(previous);
        }
    }
}
