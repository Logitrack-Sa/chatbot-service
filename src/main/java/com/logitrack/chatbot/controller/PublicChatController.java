package com.logitrack.chatbot.controller;

import java.util.concurrent.Semaphore;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Orientación pública sin herramientas ni acceso a información de cuentas. */
@RestController
public class PublicChatController {
    private final ChatClient client;
    private final Semaphore capacity = new Semaphore(2);
    private long windowStarted = System.nanoTime();
    private int requests;

    public PublicChatController(ChatModel model) {
        client = ChatClient.builder(model).defaultSystem("""
                Sos LogiBot, el asistente público de LogiTrack. Respondé en español, breve y claro.
                Ayudá únicamente con el uso de la web, preparación de paquetes, registro y pagos.
                El seguimiento público está en la sección Seguimiento y requiere el código del envío.
                Para crear envíos o consultar datos de una cuenta hay que iniciar sesión.
                Los pagos se realizan desde el detalle de un envío mediante Mercado Pago.
                No tenés acceso a envíos, usuarios, repartidores, tarifas ni estados de pagos.
                No inventes datos, precios, plazos, sucursales ni acciones realizadas.
                No pidas contraseñas, tokens ni datos de tarjetas; para pagar usá el checkout de Mercado Pago.
                Si piden información privada, indicá que ingresen a su cuenta.
                """).build();
    }

    public record Question(String prompt) {}
    public record Answer(String message) {}

    @PostMapping("/api/v1/chat/public/message")
    public ResponseEntity<Answer> answer(@RequestBody Question question) {
        if (question.prompt() == null || question.prompt().isBlank() || question.prompt().length() > 2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        reserve();
        if (!capacity.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        try {
            String text = client.prompt().user(question.prompt().trim())
                    .options(OpenAiChatOptions.builder().maxTokens(400).build()).call().content();
            if (text == null || text.isBlank()) throw new IllegalStateException();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Answer(text));
        } catch (RuntimeException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            capacity.release();
        }
    }

    private synchronized void reserve() {
        long now = System.nanoTime();
        if (now - windowStarted >= 60_000_000_000L) { windowStarted = now; requests = 0; }
        if (++requests > 30) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    }
}
