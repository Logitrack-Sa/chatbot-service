package com.logitrack.chatbot.config;

import com.logitrack.chatbot.client.CourierFeignClient;
import com.logitrack.chatbot.client.ShipmentFeignClient;
import com.logitrack.chatbot.tools.LogisticsTools;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatbotConfigTest {
    @Test void registersTheSingleChatClientWithTheSystemPromptAndBothLogisticsTools() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        LogisticsTools tools = new LogisticsTools(mock(ShipmentFeignClient.class), mock(CourierFeignClient.class));
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.defaultTools(any())).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);

        ChatClient result = new ChatbotConfig().chatClient(builder, tools);

        assertThat(result).isSameAs(chatClient);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(builder).defaultSystem(prompt.capture());
        assertThat(prompt.getValue()).isEqualTo(ChatbotConfig.SYSTEM_PROMPT);
        assertThat(prompt.getValue()).contains("Eres LogiBot", "Tienes prohibido crear", "como máximo los primeros diez");
        verify(builder).defaultTools(tools);
        verify(builder).build();

        ToolCallback[] callbacks = ToolCallbacks.from(tools);
        assertThat(Arrays.stream(callbacks).map(callback -> callback.getToolDefinition().name()).toList())
                .containsExactlyInAnyOrder("consultarEstadoEnvioPorTracking", "obtenerRepartidoresDisponibles");
        assertThat(Arrays.stream(callbacks).map(callback -> callback.getToolDefinition().description()))
                .allSatisfy(description -> assertThat(description).containsIgnoringCase("solo lectura"));
    }
}
