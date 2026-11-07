package com.logitrack.chatbot.config;

import com.logitrack.chatbot.security.JwtRelayContext;
import io.micrometer.context.ContextRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import reactor.core.publisher.Hooks;

@Configuration(proxyBeanMethods = false)
public class ContextPropagationConfiguration {
    public ContextPropagationConfiguration() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(JwtRelayContext.KEY,
                JwtRelayContext::get, JwtRelayContext::set, JwtRelayContext::clear);
        Hooks.enableAutomaticContextPropagation();
    }

    @Bean
    TaskDecorator jwtTaskDecorator() {
        return task -> {
            // No capturar ServletRequest ni propagar referencias mutables de SecurityContext.
            var captured = JwtRelayContext.get();
            if (captured == null && org.springframework.security.core.context.SecurityContextHolder.getContext()
                    .getAuthentication() instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken auth
                    && auth.isAuthenticated()) captured = auth.getToken();
            var jwt = captured;
            return () -> {
                var previous = JwtRelayContext.get();
                var previousSecurity = org.springframework.security.core.context.SecurityContextHolder.getContext();
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
                JwtRelayContext.set(jwt);
                try { task.run(); }
                finally {
                    JwtRelayContext.set(previous);
                    org.springframework.security.core.context.SecurityContextHolder.setContext(previousSecurity);
                }
            };
        };
    }

    @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
    ThreadPoolTaskExecutor applicationTaskExecutor(TaskDecorator jwtTaskDecorator) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("chatbot-async-");
        executor.setTaskDecorator(jwtTaskDecorator);
        return executor;
    }

    @Bean
    WebMvcConfigurer asyncMvcConfigurer(ThreadPoolTaskExecutor applicationTaskExecutor) {
        return new WebMvcConfigurer() {
            @Override
            public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                configurer.setTaskExecutor(applicationTaskExecutor).setDefaultTimeout(120_000);
            }
        };
    }
}
