package com.logitrack.chatbot.config;

import com.logitrack.chatbot.client.FeignClientSecurityConfig;
import com.logitrack.chatbot.security.JwtRelayContext;
import com.logitrack.chatbot.security.SecurityContextPropagationFilter;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.*;

class JwtPropagationTest {
    static ContextPropagationConfiguration configuration;
    final RequestInterceptor interceptor = new FeignClientSecurityConfig().userBearerToken();

    @BeforeAll static void initialize() { configuration = new ContextPropagationConfiguration(); }
    @AfterEach void clear() { JwtRelayContext.clear(); SecurityContextHolder.clearContext(); }

    static Jwt jwt(String value) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject("user")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }

    String header() {
        var request = new RequestTemplate();
        request.header("Authorization", "Bearer must-be-replaced");
        interceptor.apply(request);
        return request.headers().get("Authorization").iterator().next();
    }

    @Test void interceptorRejectsAbsentAndExpiredIdentity() {
        assertThatThrownBy(this::header).isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        JwtRelayContext.set(Jwt.withTokenValue("expired").header("alg", "RS256")
                .issuedAt(Instant.now().minusSeconds(100)).expiresAt(Instant.now().minusSeconds(1)).build());
        assertThatThrownBy(this::header).isInstanceOf(org.springframework.security.core.AuthenticationException.class);
    }

    @Test void filterCapturesOnlyValidatedIdentityAndRestoresOnFailure() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt("alice"), List.of()));
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer attacker-header");
        assertThatThrownBy(() -> new SecurityContextPropagationFilter().doFilter(request, new MockHttpServletResponse(),
                (req, res) -> {
                    assertThat(header()).isEqualTo("Bearer alice");
                    throw new jakarta.servlet.ServletException("test failure");
                })).isInstanceOf(jakarta.servlet.ServletException.class);
        assertThat(JwtRelayContext.get()).isNull();
    }

    @Test void reactorSurvivesDelayedSubscriptionAndDoesNotLeakOnReusedWorker() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var scheduler = Schedulers.fromExecutorService(executor);
            try {
                // Create worker before any identity exists, so inheritable ThreadLocal cannot make this pass.
                executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
                JwtRelayContext.set(jwt("alice"));
                Flux<String> first = JwtRelayContext.propagate(Mono.fromCallable(this::header).subscribeOn(scheduler).flux());
                JwtRelayContext.set(jwt("bob"));
                Flux<String> second = JwtRelayContext.propagate(Mono.fromCallable(this::header).subscribeOn(scheduler).flux());
                JwtRelayContext.clear();
                assertThat(Flux.merge(first, second).collectList().block(Duration.ofSeconds(5)))
                        .containsExactlyInAnyOrder("Bearer alice", "Bearer bob");
                assertThat(executor.submit(JwtRelayContext::get).get(2, TimeUnit.SECONDS)).isNull();
                JwtRelayContext.set(jwt("error-user"));
                var failed = JwtRelayContext.propagate(Mono.fromCallable(() -> {
                    header(); throw new IllegalStateException("failed tool");
                }).subscribeOn(scheduler).flux());
                JwtRelayContext.clear();
                assertThatThrownBy(() -> failed.blockLast(Duration.ofSeconds(5))).isInstanceOf(IllegalStateException.class);
                assertThat(executor.submit(JwtRelayContext::get).get(2, TimeUnit.SECONDS)).isNull();
            } finally { scheduler.dispose(); }
        }
    }

    @Test void decoratedTasksCarryIdentityAndClearContextBetweenUsers() throws Exception {
        var decorator = configuration.jwtTaskDecorator();
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
            var seen = new java.util.concurrent.CopyOnWriteArrayList<String>();
            for (String name : List.of("alice", "bob")) {
                SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(name), List.of()));
                var task = decorator.decorate(() -> seen.add(header()));
                SecurityContextHolder.clearContext();
                executor.submit(task).get(2, TimeUnit.SECONDS);
            }
            assertThat(seen).containsExactly("Bearer alice", "Bearer bob");
            assertThat(executor.submit(JwtRelayContext::get).get(2, TimeUnit.SECONDS)).isNull();
            assertThat(executor.submit(() -> SecurityContextHolder.getContext().getAuthentication()).get(2, TimeUnit.SECONDS)).isNull();
        }
    }
}
