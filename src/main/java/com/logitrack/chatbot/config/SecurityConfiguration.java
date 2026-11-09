package com.logitrack.chatbot.config;

import com.logitrack.chatbot.security.SecurityContextPropagationFilter;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    JwtDecoder jwtDecoder(@Value("${logitrack.security.public-key-location}") Resource publicKey,
                          @Value("${logitrack.security.issuer}") String issuer,
                          @Value("${logitrack.security.audience}") String audience,
                          @Value("${logitrack.security.key-id}") String keyId) throws IOException {
        try (var input = publicKey.getInputStream()) {
            var decoder = NimbusJwtDecoder.withPublicKey(RsaKeyConverters.x509().convert(input))
                    .signatureAlgorithm(SignatureAlgorithm.RS256).build();
            OAuth2TokenValidator<Jwt> contract = jwt -> {
                boolean valid;
                try {
                    UUID.fromString(jwt.getSubject());
                    valid = jwt.getExpiresAt() != null && jwt.getAudience().contains(audience)
                            && keyId.equals(jwt.getHeaders().get("kid"));
                } catch (RuntimeException ex) { valid = false; }
                return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Contrato JWT inválido", null));
            };
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), contract));
            return decoder;
        }
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/chat/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}))
                .addFilterAfter(new SecurityContextPropagationFilter(), BearerTokenAuthenticationFilter.class)
                .build();
    }
}
