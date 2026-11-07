package com.logitrack.chatbot;

import com.logitrack.chatbot.client.CourierFeignClient;
import com.logitrack.chatbot.client.ShipmentFeignClient;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;

/** Solo CI: servidor real con fixtures HTTP locales, sin Docker, MySQL ni proveedor de IA. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.config.enabled=false", "spring.config.import=", "spring.ai.openai.api-key=test-only",
        "spring.ai.openai.chat.options.model=fixture-model", "eureka.client.fetch-registry=false",
        "eureka.client.initial-instance-info-replication-interval-seconds=1",
        "eureka.instance.lease-renewal-interval-in-seconds=5"})
@Import(ChatbotIntegrationTest.Probes.class)
class ChatbotIntegrationTest {
    static final KeyPair KEYS;
    static final Path PUBLIC_KEY;
    static final HttpServer FIXTURE;
    static final Map<String, String> HEADERS = new ConcurrentHashMap<>();
    static final CountDownLatch REGISTERED = new CountDownLatch(1);
    static {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEYS = generator.generateKeyPair();
            PUBLIC_KEY = Files.createTempFile("chatbot-public-", ".pem");
            Files.writeString(PUBLIC_KEY, "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(KEYS.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n");
            FIXTURE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FIXTURE.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                byte[] request = exchange.getRequestBody().readAllBytes();
                if (path.startsWith("/eureka/")) {
                    if (exchange.getRequestMethod().equals("POST") && path.endsWith("/CHATBOT-SERVICE")) {
                        if (new String(request, StandardCharsets.UTF_8).contains("CHATBOT-SERVICE")) REGISTERED.countDown();
                        exchange.sendResponseHeaders(204, -1);
                    } else exchange.sendResponseHeaders(200, -1);
                } else {
                    HEADERS.put(path, String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                    String body = path.startsWith("/api/v1/shipments/")
                            ? "{\"trackingCode\":\"LT-TEST\",\"status\":\"PENDIENTE\"}"
                            : "{\"content\":[{\"id\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"Ana\",\"vehicleType\":\"Moto\",\"status\":\"AVAILABLE\"}],\"page\":{\"size\":10,\"number\":0,\"totalElements\":1,\"totalPages\":1}}";
                    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, payload.length);
                    exchange.getResponseBody().write(payload);
                }
                exchange.close();
            });
            FIXTURE.start();
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + FIXTURE.getAddress().getPort();
        registry.add("logitrack.security.public-key-location", () -> PUBLIC_KEY.toUri().toString());
        registry.add("logitrack.security.issuer", () -> "https://issuer.test");
        registry.add("logitrack.security.key-id", () -> "test-key");
        registry.add("logitrack.clients.shipment-url", () -> base);
        registry.add("logitrack.clients.courier-url", () -> base);
        registry.add("eureka.client.service-url.defaultZone", () -> base + "/eureka/");
    }

    @LocalServerPort int port;
    @Autowired org.springframework.ai.chat.client.ChatClient.Builder chatClientBuilder;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @AfterAll static void cleanup() throws Exception {
        FIXTURE.stop(0);
        Files.deleteIfExists(PUBLIC_KEY);
    }

    String token(String issuer, List<String> audience, Instant expiration) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString())
                .issuer(issuer).audience(audience).issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(expiration)).claim("roles", List.of("CLIENTE")).build();
        var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        signed.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
        return signed.serialize();
    }

    HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    String validToken() throws Exception {
        return token("https://issuer.test", List.of("chatbot-service", "shipment-service", "courier-service"), Instant.now().plusSeconds(300));
    }

    @Test void metricsDiscoveryAndAiBuilderAreAvailableWithoutCloudCalls() throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
        var metrics = get("/actuator/prometheus", null);
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("jvm_memory_used_bytes");
        assertThat(chatClientBuilder).isNotNull();
        assertThat(REGISTERED.await(15, TimeUnit.SECONDS)).isTrue();
    }

    @Test void jwtIsValidatedLocallyBeforeProtectedEndpoints() throws Exception {
        String path = "/api/v1/chat/probe?code=security";
        assertThat(get(path, null).statusCode()).isEqualTo(401);
        assertThat(get(path, "invalid").statusCode()).isEqualTo(401);
        assertThat(get(path, token("https://other.test", List.of("chatbot-service"), Instant.now().plusSeconds(300))).statusCode()).isEqualTo(401);
        assertThat(get(path, token("https://issuer.test", List.of("courier-service"), Instant.now().plusSeconds(300))).statusCode()).isEqualTo(401);
        assertThat(get(path, token("https://issuer.test", List.of("chatbot-service"), Instant.now().minusSeconds(120))).statusCode()).isEqualTo(401);
        assertThat(HEADERS).doesNotContainKey("/api/v1/shipments/tracking/security");
    }

    @Test void mvcFluxAndAsyncFeignCallsRelayTheSameJwt() throws Exception {
        String jwt = validToken();
        var streamed = get("/api/v1/chat/probe?code=flux", jwt);
        assertThat(streamed.statusCode()).isEqualTo(200);
        assertThat(streamed.body()).contains("PENDIENTE", "Ana");
        assertThat(HEADERS.get("/api/v1/shipments/tracking/flux")).isEqualTo("Bearer " + jwt);
        assertThat(HEADERS.get("/api/v1/couriers")).isEqualTo("Bearer " + jwt);
        assertThat(get("/api/v1/chat/async-probe", jwt).statusCode()).isEqualTo(200);
        assertThat(HEADERS.get("/api/v1/shipments/tracking/async")).isEqualTo("Bearer " + jwt);
    }

    @Test void concurrentHttpStreamsDoNotExchangeUserTokens() throws Exception {
        String alice = validToken();
        String bob = validToken();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> get("/api/v1/chat/probe?code=alice", alice));
            var second = executor.submit(() -> get("/api/v1/chat/probe?code=bob", bob));
            assertThat(first.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(second.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        assertThat(HEADERS.get("/api/v1/shipments/tracking/alice")).isEqualTo("Bearer " + alice);
        assertThat(HEADERS.get("/api/v1/shipments/tracking/bob")).isEqualTo("Bearer " + bob);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Probes {
        @Bean ProbeController probeController(ShipmentFeignClient shipments, CourierFeignClient couriers, AsyncProbe async) {
            return new ProbeController(shipments, couriers, async);
        }
        @Bean AsyncProbe asyncProbe(ShipmentFeignClient shipments) { return new AsyncProbe(shipments); }
    }

    static class AsyncProbe {
        final ShipmentFeignClient shipments;
        AsyncProbe(ShipmentFeignClient shipments) { this.shipments = shipments; }
        @Async public CompletableFuture<String> fetch() {
            return CompletableFuture.completedFuture(shipments.findByTrackingCode("async").status());
        }
    }

    @org.springframework.boot.test.context.TestComponent
    @RestController
    static class ProbeController {
        final ShipmentFeignClient shipments;
        final CourierFeignClient couriers;
        final AsyncProbe async;
        ProbeController(ShipmentFeignClient shipments, CourierFeignClient couriers, AsyncProbe async) {
            this.shipments = shipments; this.couriers = couriers; this.async = async;
        }
        @GetMapping(value = "/api/v1/chat/probe", produces = "text/event-stream")
        Flux<String> stream(@RequestParam String code) {
            // Deliberately no explicit contextWrite: Spring MVC must capture the registered accessor.
            return Flux.defer(() -> Flux.just(shipments.findByTrackingCode(code).status(),
                    couriers.findCouriersByStatus("AVAILABLE", 0, 10).content().getFirst().name()))
                    .subscribeOn(Schedulers.boundedElastic());
        }
        @GetMapping("/api/v1/chat/async-probe")
        CompletableFuture<String> async() { return async.fetch(); }
    }
}
