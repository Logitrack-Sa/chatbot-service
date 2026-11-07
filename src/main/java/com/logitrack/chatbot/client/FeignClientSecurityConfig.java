package com.logitrack.chatbot.client;

import com.logitrack.chatbot.security.JwtRelayContext;
import feign.Logger;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;

/** Configuración por cliente, deliberadamente fuera del component scan de @Configuration. */
public class FeignClientSecurityConfig {
    @Bean
    public RequestInterceptor userBearerToken() {
        return request -> {
            String value = JwtRelayContext.requireJwt().getTokenValue();
            request.removeHeader(HttpHeaders.AUTHORIZATION);
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + value);
        };
    }

    @Bean
    feign.Request.Options requestOptions() {
        return new feign.Request.Options(3000, java.util.concurrent.TimeUnit.MILLISECONDS,
                10000, java.util.concurrent.TimeUnit.MILLISECONDS, false);
    }

    @Bean
    Logger.Level feignLoggerLevel() { return Logger.Level.NONE; }
}
