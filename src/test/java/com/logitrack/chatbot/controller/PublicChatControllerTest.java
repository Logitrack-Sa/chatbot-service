package com.logitrack.chatbot.controller;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicChatControllerTest {
    @Test void publicAnswerUsesAnIsolatedClientWithoutPrivateTools() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Usá Seguimiento con tu código.")))));
        var controller = new PublicChatController(model);
        var answer = controller.answer(new PublicChatController.Question("¿Dónde sigo mi paquete?"));
        assertThat(answer.getBody().message()).contains("Seguimiento");
        assertThat(answer.getHeaders().getCacheControl()).isEqualTo("no-store");
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolCallbacks()).isNullOrEmpty();
        assertThat(options.getToolNames()).isNullOrEmpty();
    }

    @Test void rejectsBlankLongPromptsAndFlooding() {
        var model = mock(ChatModel.class);
        var controller = new PublicChatController(model);
        clearInvocations(model);
        assertThatThrownBy(() -> controller.answer(new PublicChatController.Question(" ")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> controller.answer(new PublicChatController.Question("a".repeat(2001))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(model);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Hola")))));
        for (int i=0;i<30;i++) controller.answer(new PublicChatController.Question("Hola"));
        assertThatThrownBy(() -> controller.answer(new PublicChatController.Question("Hola")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting(e -> ((org.springframework.web.server.ResponseStatusException)e).getStatusCode().value()).isEqualTo(429);
        verify(model,times(30)).call(any(Prompt.class));
    }
}
