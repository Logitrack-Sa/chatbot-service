package com.logitrack.chatbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import feign.RequestInterceptor;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.config.enabled=false", "spring.config.import=", "spring.ai.openai.api-key=test-only",
        "spring.ai.openai.chat.options.model=fixture-model", "spring.ai.retry.max-attempts=1",
        "eureka.client.enabled=false", "eureka.client.fetch-registry=false", "eureka.client.register-with-eureka=false"})
@ActiveProfiles("test")
@Import(ChatbotStreamingIntegrationTest.FeignThreadConfiguration.class)
class ChatbotStreamingIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern TRACKING = Pattern.compile("LT-[A-Z0-9-]+");
    static final KeyPair KEYS;
    static final Path PUBLIC_KEY;
    static final HttpServer FIXTURE;
    static final Map<String, String> DOMAIN_AUTHORIZATION = new ConcurrentHashMap<>();
    static final Map<String, String> DOMAIN_QUERIES = new ConcurrentHashMap<>();
    static final List<String> AI_AUTHORIZATION = new CopyOnWriteArrayList<>();
    static final List<String> AI_BODIES = new CopyOnWriteArrayList<>();
    static final AtomicInteger DOMAIN_WRITES = new AtomicInteger();
    static final AtomicBoolean COURIER_REJECTS_AVAILABLE = new AtomicBoolean();
    static final AtomicReference<CountDownLatch> FIRST_CHUNK = new AtomicReference<>();
    static final AtomicReference<CountDownLatch> HOLD_AFTER_FIRST_CHUNK = new AtomicReference<>();

    static {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEYS = generator.generateKeyPair();
            PUBLIC_KEY = Files.createTempFile("chatbot-stream-public-", ".pem");
            Files.writeString(PUBLIC_KEY, "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(KEYS.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n");
            FIXTURE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FIXTURE.createContext("/", ChatbotStreamingIntegrationTest::handle);
            FIXTURE.start();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + FIXTURE.getAddress().getPort();
        registry.add("logitrack.security.public-key-location", () -> PUBLIC_KEY.toUri().toString());
        registry.add("logitrack.security.issuer", () -> "https://issuer.test");
        registry.add("logitrack.security.key-id", () -> "test-key");
        registry.add("logitrack.clients.shipment-url", () -> base);
        registry.add("logitrack.clients.courier-url", () -> base);
        registry.add("spring.ai.openai.base-url", () -> base);
    }

    @LocalServerPort int port;
    @Autowired ThreadRecorder threadRecorder;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach void resetFixture() {
        DOMAIN_AUTHORIZATION.clear();
        DOMAIN_QUERIES.clear();
        AI_AUTHORIZATION.clear();
        AI_BODIES.clear();
        DOMAIN_WRITES.set(0);
        COURIER_REJECTS_AVAILABLE.set(false);
        FIRST_CHUNK.set(null);
        HOLD_AFTER_FIRST_CHUNK.set(null);
        threadRecorder.threads.clear();
    }

    @AfterAll static void cleanup() throws Exception {
        FIXTURE.stop(0);
        Files.deleteIfExists(PUBLIC_KEY);
    }

    String token(String subject) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(subject)
                .issuer("https://issuer.test")
                .audience(List.of("chatbot-service", "shipment-service", "courier-service"))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("roles", List.of("CLIENTE")).build();
        var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        signed.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
        return signed.serialize();
    }

    HttpResponse<String> stream(String prompt, String jwt) throws Exception {
        var uri = URI.create("http://127.0.0.1:" + port + "/api/v1/chat/stream?prompt="
                + java.net.URLEncoder.encode(prompt, StandardCharsets.UTF_8).replace("+", "%20"));
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET();
        if (jwt != null) builder.header("Authorization", "Bearer " + jwt);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void realChatClientToolFeignSseCircuitRelaysTheUserJwtOnlyToDomainServices() throws Exception {
        String jwt = token(UUID.randomUUID().toString());

        HttpResponse<String> response = stream("¿Dónde está el envío LT-123?", jwt);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");
        assertThat(response.body()).contains("El envío LT-123 está", "en tránsito.");
        assertThat(DOMAIN_AUTHORIZATION.get("LT-123")).isEqualTo("Bearer " + jwt);
        assertThat(AI_AUTHORIZATION).isNotEmpty().allMatch("Bearer test-only"::equals);
        assertThat(AI_BODIES).allSatisfy(body -> assertThat(body).doesNotContain(jwt));
        assertThat(AI_BODIES.getFirst()).contains("Eres LogiBot", "consultarEstadoEnvioPorTracking",
                "obtenerRepartidoresDisponibles");
        assertThat(AI_BODIES.getLast()).contains("\"role\":\"tool\"", "LT-123");
        assertThat(DOMAIN_WRITES.get()).isZero();
        assertThat(threadRecorder.threads).isNotEmpty()
                .allSatisfy(thread -> assertThat(thread).contains("boundedElastic"));
    }

    @Test void simulatedProviderInvokesAvailableCouriersWithTheFixedQueryAndRelaysJwtOnlyToDomain()
            throws Exception {
        String jwt = token(UUID.randomUUID().toString());

        HttpResponse<String> response = stream("¿Qué repartidores están disponibles? COURIER-QUERY", jwt);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Hay 1 repartidor disponible.");
        assertThat(DOMAIN_QUERIES.get("/api/v1/couriers")).isEqualTo("status=AVAILABLE&page=0&size=10");
        assertThat(DOMAIN_AUTHORIZATION.get("couriers")).isEqualTo("Bearer " + jwt);
        assertThat(AI_AUTHORIZATION).isNotEmpty().allMatch("Bearer test-only"::equals);
        assertThat(AI_BODIES).allSatisfy(body -> assertThat(body).doesNotContain(jwt));
        assertThat(AI_BODIES.getLast()).contains("\"role\":\"tool\"", "SUCCESS", "Ana");
        assertThat(DOMAIN_WRITES.get()).isZero();
    }

    @Test void simulatedProviderReceivesUnavailableWhenCourierRejectsTheAvailableContract() throws Exception {
        COURIER_REJECTS_AVAILABLE.set(true);
        String jwt = token(UUID.randomUUID().toString());

        HttpResponse<String> response = stream("¿Qué repartidores están disponibles? COURIER-QUERY", jwt);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("rechazó el contrato AVAILABLE");
        assertThat(DOMAIN_QUERIES.get("/api/v1/couriers")).isEqualTo("status=AVAILABLE&page=0&size=10");
        assertThat(DOMAIN_AUTHORIZATION.get("couriers")).isEqualTo("Bearer " + jwt);
        assertThat(AI_AUTHORIZATION).isNotEmpty().allMatch("Bearer test-only"::equals);
        assertThat(AI_BODIES).allSatisfy(body -> assertThat(body).doesNotContain(jwt));
        assertThat(AI_BODIES.getLast()).contains("\"role\":\"tool\"", "UNAVAILABLE", "contrato");
        assertThat(DOMAIN_WRITES.get()).isZero();
    }

    @Test void firstSseFragmentArrivesBeforeTheModelFinishesTheGeneration() throws Exception {
        String jwt = token(UUID.randomUUID().toString());
        CountDownLatch firstChunk = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        FIRST_CHUNK.set(firstChunk);
        HOLD_AFTER_FIRST_CHUNK.set(hold);
        var uri = URI.create("http://127.0.0.1:" + port + "/api/v1/chat/stream?prompt="
                + java.net.URLEncoder.encode("Consultá LT-STREAM", StandardCharsets.UTF_8).replace("+", "%20"));
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + jwt).GET().build();
        try (var executor = Executors.newSingleThreadExecutor()) {
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                assertThat(response.statusCode()).isEqualTo(200);
                Future<String> firstEvent = executor.submit(() -> nextDataLine(reader));
                String line = firstEvent.get(5, TimeUnit.SECONDS);
                assertThat(line).contains("El envío LT-STREAM está");
                assertThat(hold.getCount()).isEqualTo(1);
                hold.countDown();
                String rest = reader.lines().collect(Collectors.joining("\n"));
                assertThat(rest).contains("en tránsito.");
            }
        } finally {
            hold.countDown();
        }
    }

    @Test void twoConcurrentUsersKeepTheirOwnTokensAndToolResults() throws Exception {
        String alice = token(UUID.randomUUID().toString());
        String bob = token(UUID.randomUUID().toString());
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> first = executor.submit(() -> stream("Consultá LT-ALICE", alice));
            Future<HttpResponse<String>> second = executor.submit(() -> stream("Consultá LT-BOB", bob));
            assertThat(first.get(20, TimeUnit.SECONDS).body()).contains("LT-ALICE");
            assertThat(second.get(20, TimeUnit.SECONDS).body()).contains("LT-BOB");
        }
        assertThat(DOMAIN_AUTHORIZATION.get("LT-ALICE")).isEqualTo("Bearer " + alice);
        assertThat(DOMAIN_AUTHORIZATION.get("LT-BOB")).isEqualTo("Bearer " + bob);
        assertThat(AI_AUTHORIZATION).isNotEmpty().allMatch("Bearer test-only"::equals);
        assertThat(DOMAIN_WRITES.get()).isZero();
    }

    @Test void unauthenticatedAndInvalidPromptsAreRejectedBeforeAiOrDomainCalls() throws Exception {
        assertThat(stream("Consultá LT-123", null).statusCode()).isEqualTo(401);
        HttpResponse<String> blank = stream("   ", token(UUID.randomUUID().toString()));
        assertThat(blank.statusCode()).as("body=%s headers=%s", blank.body(), blank.headers().map())
                .isEqualTo(400);
        assertThat(AI_BODIES).isEmpty();
        assertThat(DOMAIN_AUTHORIZATION).isEmpty();
        assertThat(DOMAIN_WRITES.get()).isZero();
    }

    @Test void simulatedCancellationConversationKeepsSystemRulesAndDoesNotWrite() throws Exception {
        String jwt = token(UUID.randomUUID().toString());

        HttpResponse<String> response = stream("Cancelá el envío LT-CANCEL", jwt);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("No puedo cancelar", "panel de gestión");
        assertThat(AI_BODIES).hasSize(1);
        assertThat(AI_BODIES.getFirst()).contains("Tienes prohibido crear", "Nunca inventes",
                "No afirmes haber realizado operaciones de escritura");
        assertThat(DOMAIN_AUTHORIZATION).isEmpty();
        assertThat(DOMAIN_WRITES.get()).isZero();
    }

    @Test void providerFailureBeforeSseDoesNotTurnIntoAFakeSuccessfulAnswer() throws Exception {
        HttpResponse<String> response = stream("FAIL-BEFORE", token(UUID.randomUUID().toString()));

        assertThat(response.statusCode()).isNotEqualTo(200);
        assertThat(response.body()).doesNotContain("No puedo cancelar", "El envío");
        assertThat(DOMAIN_WRITES.get()).isZero();
    }

    static String nextDataLine(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("data:")) return line;
        }
        return null;
    }

    static void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/v1/chat/completions")) {
                handleAi(exchange);
            } else if (path.startsWith("/api/v1/")) {
                handleDomain(exchange);
            } else {
                send(exchange, 404, "application/json", "{}");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    static void handleDomain(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method)) {
            DOMAIN_WRITES.incrementAndGet();
            send(exchange, 405, "application/json", "{\"error\":\"read only\"}");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String authorization = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
        if (path.startsWith("/api/v1/shipments/tracking/")) {
            String code = path.substring("/api/v1/shipments/tracking/".length());
            DOMAIN_AUTHORIZATION.put(code, authorization);
            String body = JSON.writeValueAsString(Map.of(
                    "id", UUID.randomUUID().toString(),
                    "trackingCode", code,
                    "status", "EN_RUTA",
                    "origin", Map.of("city", "Buenos Aires"),
                    "destination", Map.of("city", "Córdoba"),
                    "weightKg", 1.5,
                    "courierId", UUID.randomUUID().toString()));
            send(exchange, 200, "application/json", body);
            return;
        }
        if (path.equals("/api/v1/couriers")) {
            DOMAIN_AUTHORIZATION.put("couriers", authorization);
            DOMAIN_QUERIES.put(path, String.valueOf(exchange.getRequestURI().getRawQuery()));
            if (COURIER_REJECTS_AVAILABLE.get()) {
                send(exchange, 400, "application/json", "{\"error\":\"invalid status\"}");
                return;
            }
            String body = JSON.writeValueAsString(Map.of(
                    "content", List.of(Map.of("id", UUID.randomUUID().toString(), "name", "Ana",
                            "vehicleType", "Moto", "status", "AVAILABLE")),
                    "page", Map.of("size", 10, "number", 0, "totalElements", 1, "totalPages", 1)));
            send(exchange, 200, "application/json", body);
            return;
        }
        DOMAIN_AUTHORIZATION.put(path, authorization);
        send(exchange, 404, "application/json", "{}");
    }

    static void handleAi(HttpExchange exchange) throws IOException, InterruptedException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        AI_BODIES.add(body);
        AI_AUTHORIZATION.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        if (body.contains("FAIL-BEFORE")) {
            send(exchange, 503, "application/json", "{\"error\":{\"message\":\"fixture unavailable\"}}");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        OutputStream output = exchange.getResponseBody();
        if (body.contains("LT-CANCEL")) {
            writeContent(output, "No puedo cancelar envíos desde este asistente. ");
            writeContent(output, "Debés usar el panel de gestión con los permisos necesarios.");
            writeFinish(output, "stop");
            writeDone(output);
            return;
        }
        boolean toolFollowUp = body.contains("\"role\":\"tool\"") || body.contains("\"tool_call_id\"");
        if (body.contains("COURIER-QUERY")) {
            if (toolFollowUp) {
                writeContent(output, COURIER_REJECTS_AVAILABLE.get()
                        ? "El servicio rechazó el contrato AVAILABLE."
                        : "Hay 1 repartidor disponible.");
            } else {
                writeCourierToolCall(output);
            }
            writeFinish(output, toolFollowUp ? "stop" : "tool_calls");
            writeDone(output);
            return;
        }
        String trackingCode = trackingCode(body);
        if (toolFollowUp) {
            writeContent(output, "El envío " + trackingCode + " está ");
            if ("LT-STREAM".equals(trackingCode)) {
                CountDownLatch first = FIRST_CHUNK.get();
                if (first != null) first.countDown();
                CountDownLatch hold = HOLD_AFTER_FIRST_CHUNK.get();
                if (hold != null) hold.await(15, TimeUnit.SECONDS);
            }
            writeContent(output, "en tránsito.");
            writeFinish(output, "stop");
            writeDone(output);
            return;
        }
        writeToolCall(output, trackingCode);
        writeFinish(output, "tool_calls");
        writeDone(output);
    }

    static String trackingCode(String body) {
        var matcher = TRACKING.matcher(body);
        return matcher.find() ? matcher.group() : "LT-UNKNOWN";
    }

    static void writeToolCall(OutputStream output, String trackingCode) throws IOException {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "consultarEstadoEnvioPorTracking");
        function.put("arguments", JSON.writeValueAsString(Map.of("trackingCode", trackingCode)));
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("index", 0);
        toolCall.put("id", "call_1");
        toolCall.put("type", "function");
        toolCall.put("function", function);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("role", "assistant");
        delta.put("tool_calls", List.of(toolCall));
        writeChunk(output, delta, null);
    }

    static void writeCourierToolCall(OutputStream output) throws IOException {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "obtenerRepartidoresDisponibles");
        function.put("arguments", "{}");
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("index", 0);
        toolCall.put("id", "call_couriers");
        toolCall.put("type", "function");
        toolCall.put("function", function);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("role", "assistant");
        delta.put("tool_calls", List.of(toolCall));
        writeChunk(output, delta, null);
    }

    static void writeContent(OutputStream output, String content) throws IOException {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("role", "assistant");
        delta.put("content", content);
        writeChunk(output, delta, null);
    }

    static void writeFinish(OutputStream output, String reason) throws IOException {
        writeChunk(output, new LinkedHashMap<>(), reason);
    }

    static void writeChunk(OutputStream output, Map<String, Object> delta, String finishReason) throws IOException {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", "chatcmpl-fixture");
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", 1);
        chunk.put("model", "fixture-model");
        chunk.put("choices", List.of(choice));
        output.write(("data: " + JSON.writeValueAsString(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    static void writeDone(OutputStream output) throws IOException {
        output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.getResponseBody().flush();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FeignThreadConfiguration {
        @Bean ThreadRecorder threadRecorder() {
            return new ThreadRecorder();
        }

        @Bean RequestInterceptor recordingRequestInterceptor(ThreadRecorder recorder) {
            return template -> recorder.threads.add(Thread.currentThread().getName());
        }
    }

    static class ThreadRecorder {
        final List<String> threads = new CopyOnWriteArrayList<>();
    }
}
