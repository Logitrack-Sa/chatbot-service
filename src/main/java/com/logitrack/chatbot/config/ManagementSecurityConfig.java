package com.logitrack.chatbot.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "management.server.port")
public class ManagementSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests((authorize) -> authorize
                        .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                        .anyRequest().denyAll())
                .csrf((csrf) -> csrf.disable())
                .httpBasic((basic) -> basic.disable())
                .formLogin((login) -> login.disable());
        return http.build();
    }
}
