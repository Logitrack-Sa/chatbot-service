package com.logitrack.chatbot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
        "management.endpoints.web.exposure.include=health,info"
})
@ActiveProfiles("test")
class BusinessListenerHealthWithoutManagementPortTest {

    static final KeyPair KEYS;
    static final Path PUBLIC_KEY;

    static {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEYS = generator.generateKeyPair();
            PUBLIC_KEY = Files.createTempFile("chatbot-no-mgmt-public-", ".pem");
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

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    void healthStaysAvailableAndTechnicalEndpointsRemainClosed() throws Exception {
        var health = get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        var prometheus = get("/actuator/prometheus");
        assertThat(prometheus.statusCode()).isIn(401, 403, 404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + businessPort + path))
                .timeout(Duration.ofSeconds(15))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
