package com.logitrack.chatbot.controller;

import com.logitrack.chatbot.security.JwtRelayContext;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatbotControllerTest {
    ChatClient chatClient;
    ChatClient.ChatClientRequestSpec requestSpec;
    ChatClient.StreamResponseSpec streamSpec;
    MockMvc mvc;

    @BeforeEach void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        mvc = MockMvcBuilders.standaloneSetup(new ChatbotController(chatClient)).build();
        JwtRelayContext.set(Jwt.withTokenValue("alice").header("alg", "RS256").subject("user")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
    }

    @AfterEach void clear() {
        JwtRelayContext.clear();
    }

    @Test void streamsProviderFragmentsWithEventStreamContentType() throws Exception {
        when(streamSpec.content()).thenReturn(Flux.just("Hola", " ", "mundo"));

        MvcResult started = mvc.perform(get("/api/v1/chat/stream").param("prompt", "¿Dónde está LT-123?"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Hola")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("mundo")));

        verify(requestSpec).user("¿Dónde está LT-123?");
        verify(requestSpec).stream();
        verify(streamSpec).content();
    }

    @Test void blankPromptIsRejectedBeforeCallingTheModel() throws Exception {
        mvc.perform(get("/api/v1/chat/stream").param("prompt", "   "))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatClient);
    }

    @Test void missingPromptIsRejectedBeforeCallingTheModel() throws Exception {
        mvc.perform(get("/api/v1/chat/stream"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatClient);
    }

    @Test void directBlankPromptFailsWithBadRequestStatus() {
        ChatbotController controller = new ChatbotController(chatClient);
        assertThatThrownBy(() -> controller.streamPrompt("\t"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting(error -> ((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(400);
        verifyNoInteractions(chatClient);
    }
}
