package com.logitrack.chatbot;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.config.enabled=false",
        "spring.config.import=",
        "spring.ai.openai.api-key=test-only",
        "spring.ai.openai.chat.options.model=fixture-model",
        "eureka.client.enabled=false",
        "management.server.port=0",
        "management.server.address=127.0.0.1",
        "management.endpoints.web.exposure.include=health,info,prometheus",
        "management.endpoint.prometheus.enabled=true",
        "management.prometheus.metrics.export.enabled=true",
        "management.metrics.tags.application=chatbot-service",
        "management.metrics.distribution.percentiles-histogram.http.server.requests=true"
})
@ActiveProfiles("test")
class ManagementListenerSeparationTest {

    static final KeyPair KEYS;
    static final Path PUBLIC_KEY;

    static {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEYS = generator.generateKeyPair();
            PUBLIC_KEY = Files.createTempFile("chatbot-mgmt-public-", ".pem");
            PUBLIC_KEY.toFile().deleteOnExit();
            Files.writeString(PUBLIC_KEY, "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(KEYS.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n");
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("logitrack.security.public-key-location", () -> PUBLIC_KEY.toUri().toString());
        registry.add("logitrack.security.issuer", () -> "https://issuer.test");
        registry.add("logitrack.security.key-id", () -> "test-ephemeral");
    }

    @LocalServerPort
    int businessPort;

    @Autowired
    Environment environment;

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    void managementListenerExposesPrometheusWithApplicationTag() throws Exception {
        var response = get(managementPort(), "/actuator/prometheus", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("application=\"chatbot-service\"");
    }

    @Test
    void managementListenerExposesHealth() throws Exception {
        assertThat(get(managementPort(), "/actuator/health", null).statusCode()).isEqualTo(200);
    }

    @Test
    void trafficGeneratesHttpPercentilesBuckets() throws Exception {
        // Tráfico al listener de negocio con JWT
        get(businessPort, "/api/v1/chat/history", validToken());

        // Tráfico al listener de gestión
        get(managementPort(), "/actuator/health", null);

        var response = get(managementPort(), "/actuator/prometheus", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("http_server_requests_seconds_bucket{");
        assertThat(response.body()).contains("le=");
        assertThat(response.body()).contains("http_server_requests_seconds_count");
    }

    @Test
    void businessListenerDoesNotExposePrometheusOrInfo() throws Exception {
        for (String path : new String[] { "/actuator/prometheus", "/actuator/info" }) {
            assertThat(get(businessPort, path, null).statusCode()).isIn(401, 403, 404);
        }
    }

    @Test
    void businessListenerRejectsTechnicalEndpointsEvenWithValidJwt() throws Exception {
        String jwt = validToken();
        for (String path : new String[] { "/actuator/prometheus", "/actuator/info" }) {
            assertThat(get(businessPort, path, jwt).statusCode()).isIn(401, 403, 404);
        }
    }

    @Test
    void administrativeEndpointsAreNotExposed() throws Exception {
        for (String path : new String[] { "/actuator/env", "/actuator/beans", "/actuator/threaddump" }) {
            assertThat(get(businessPort, path, null).statusCode()).isIn(401, 403, 404, 405);
            assertThat(get(managementPort(), path, null).statusCode()).isIn(401, 403, 404, 405);
        }
        // POST /actuator/shutdown debe ser rechazado en ambos listeners
        assertThat(post(businessPort, "/actuator/shutdown").statusCode()).isIn(401, 403, 404, 405);
        assertThat(post(managementPort(), "/actuator/shutdown").statusCode()).isIn(401, 403, 404, 405);
    }

    private String validToken() throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .issuer("https://issuer.test")
                .audience(List.of("chatbot-service"))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("roles", List.of("CLIENTE"))
                .build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-ephemeral").build(), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
        return jwt.serialize();
    }

    private HttpResponse<String> get(int port, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(int port, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(15));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int managementPort() {
        return Integer.parseInt(environment.getRequiredProperty("local.management.port"));
    }
}
