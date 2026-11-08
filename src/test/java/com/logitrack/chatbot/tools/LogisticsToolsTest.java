package com.logitrack.chatbot.tools;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.logitrack.chatbot.client.CourierFeignClient;
import com.logitrack.chatbot.client.ShipmentFeignClient;
import com.logitrack.chatbot.dto.CourierSummaryDto;
import com.logitrack.chatbot.dto.PageResponse;
import com.logitrack.chatbot.dto.ShipmentDetailsDto;
import com.logitrack.chatbot.dto.ToolQueryResult;
import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class LogisticsToolsTest {
    final ShipmentFeignClient shipments = mock(ShipmentFeignClient.class);
    final CourierFeignClient couriers = mock(CourierFeignClient.class);
    final LogisticsTools tools = new LogisticsTools(shipments, couriers);
    final Request request = Request.create(Request.HttpMethod.GET, "http://internal.test/resource",
            Map.of(), null, StandardCharsets.UTF_8);

    static ShipmentDetailsDto shipment() {
        return new ShipmentDetailsDto(UUID.randomUUID(), "LT-123", "EN_RUTA",
                JsonNodeFactory.instance.objectNode().put("city", "Buenos Aires"),
                JsonNodeFactory.instance.objectNode().put("city", "Córdoba"),
                new BigDecimal("1.5"), UUID.randomUUID());
    }

    static PageResponse<CourierSummaryDto> courierPage(List<CourierSummaryDto> content) {
        return new PageResponse<>(content, new PageResponse.PageMetadata(10, 0, content == null ? 0 : content.size(), 1));
    }

    @Test void existingTrackingUsesTheCorrectCodeAndReturnsSuccess() {
        ShipmentDetailsDto expected = shipment();
        when(shipments.findByTrackingCode("LT-123")).thenReturn(expected);

        ToolQueryResult<ShipmentDetailsDto> result = tools.consultarEstadoEnvioPorTracking("  LT-123  ");

        assertThat(result.outcome()).isEqualTo(ToolQueryResult.SUCCESS);
        assertThat(result.data()).isSameAs(expected);
        assertThat(result.message()).isNotBlank();
        verify(shipments).findByTrackingCode("LT-123");
        verifyNoInteractions(couriers);
    }

    @Test void missingTrackingReturnsNotFoundWithoutExposingFeignDetails() {
        when(shipments.findByTrackingCode("LT-404")).thenThrow(new FeignException.NotFound(
                "internal path and token detail", request, new byte[0], Map.of()));

        ToolQueryResult<ShipmentDetailsDto> result = tools.consultarEstadoEnvioPorTracking("LT-404");

        assertThat(result.outcome()).isEqualTo(ToolQueryResult.NOT_FOUND);
        assertThat(result.data()).isNull();
        assertThat(result.message()).doesNotContain("internal", "token", "http");
    }

    @Test void blankTrackingIsRejectedWithoutCallingFeign() {
        for (String code : new String[]{null, "", "   "}) {
            ToolQueryResult<ShipmentDetailsDto> result = tools.consultarEstadoEnvioPorTracking(code);
            assertThat(result.outcome()).isEqualTo(ToolQueryResult.INVALID_INPUT);
            assertThat(result.data()).isNull();
        }
        verifyNoInteractions(shipments, couriers);
    }

    @Test void availableCouriersAreRequestedWithTheExactPaginationAndReturned() {
        CourierSummaryDto courier = new CourierSummaryDto(UUID.randomUUID(), "Ana", "Moto", "AVAILABLE");
        when(couriers.findCouriersByStatus("AVAILABLE", 0, 10)).thenReturn(courierPage(List.of(courier)));

        ToolQueryResult<List<CourierSummaryDto>> result = tools.obtenerRepartidoresDisponibles();

        assertThat(result.outcome()).isEqualTo(ToolQueryResult.SUCCESS);
        assertThat(result.data()).containsExactly(courier);
        verify(couriers).findCouriersByStatus("AVAILABLE", 0, 10);
        verifyNoInteractions(shipments);
    }

    @Test void emptyCourierPageIsSuccessWithAnExplicitMessage() {
        when(couriers.findCouriersByStatus("AVAILABLE", 0, 10)).thenReturn(courierPage(List.of()));

        ToolQueryResult<List<CourierSummaryDto>> result = tools.obtenerRepartidoresDisponibles();

        assertThat(result.outcome()).isEqualTo(ToolQueryResult.SUCCESS);
        assertThat(result.data()).isEmpty();
        assertThat(result.message()).containsIgnoringCase("no hay");
    }

    @Test void nullOrMalformedCourierResponsesAreUnavailable() {
        when(couriers.findCouriersByStatus("AVAILABLE", 0, 10)).thenReturn(null);
        assertThat(tools.obtenerRepartidoresDisponibles().outcome()).isEqualTo(ToolQueryResult.UNAVAILABLE);

        when(couriers.findCouriersByStatus("AVAILABLE", 0, 10)).thenReturn(courierPage(null));
        assertThat(tools.obtenerRepartidoresDisponibles().outcome()).isEqualTo(ToolQueryResult.UNAVAILABLE);

        when(couriers.findCouriersByStatus("AVAILABLE", 0, 10))
                .thenReturn(courierPage(java.util.Arrays.asList((CourierSummaryDto) null)));
        assertThat(tools.obtenerRepartidoresDisponibles().outcome()).isEqualTo(ToolQueryResult.UNAVAILABLE);
    }

    @Test void courierContractRejectionIsUnavailableAfterASingleFixedQuery() {
        when(couriers.findCouriersByStatus("AVAILABLE", 0, 10)).thenThrow(new FeignException.BadRequest(
                "internal courier contract rejects AVAILABLE token detail", request, new byte[0], Map.of()));

        ToolQueryResult<List<CourierSummaryDto>> result = tools.obtenerRepartidoresDisponibles();

        assertThat(result.outcome()).isEqualTo(ToolQueryResult.UNAVAILABLE);
        assertThat(result.data()).isNull();
        assertThat(result.message()).contains("AVAILABLE", "contrato");
        assertThat(result.message()).doesNotContain("internal", "token", "http", "bad request");
        verify(couriers).findCouriersByStatus("AVAILABLE", 0, 10);
        verifyNoMoreInteractions(couriers);
        verifyNoInteractions(shipments);
    }

    @Test void accessDeniedAndServerAndTransportErrorsAreDistinguished() {
        when(shipments.findByTrackingCode("LT-401")).thenThrow(new FeignException.Unauthorized(
                "no token", request, new byte[0], Map.of()));
        when(shipments.findByTrackingCode("LT-403")).thenThrow(new FeignException.Forbidden(
                "no role", request, new byte[0], Map.of()));
        when(shipments.findByTrackingCode("LT-500")).thenThrow(new FeignException.InternalServerError(
                "boom", request, new byte[0], Map.of()));
        when(shipments.findByTrackingCode("LT-503")).thenThrow(new FeignException.ServiceUnavailable(
                "down", request, new byte[0], Map.of()));
        when(shipments.findByTrackingCode("LT-TIMEOUT")).thenThrow(new RetryableException(503,
                "read timed out", Request.HttpMethod.GET, new SocketTimeoutException("timeout"), 1000L, request));
        when(shipments.findByTrackingCode("LT-400")).thenThrow(new FeignException.BadRequest(
                "bad request", request, new byte[0], Map.of()));

        assertThat(tools.consultarEstadoEnvioPorTracking("LT-401").outcome()).isEqualTo(ToolQueryResult.ACCESS_DENIED);
        assertThat(tools.consultarEstadoEnvioPorTracking("LT-403").outcome()).isEqualTo(ToolQueryResult.ACCESS_DENIED);
        assertThat(tools.consultarEstadoEnvioPorTracking("LT-500").outcome()).isEqualTo(ToolQueryResult.UNAVAILABLE);
        assertThat(tools.consultarEstadoEnvioPorTracking("LT-503").outcome()).isEqualTo(ToolQueryResult.UNAVAILABLE);
        assertThat(tools.consultarEstadoEnvioPorTracking("LT-TIMEOUT").outcome()).isEqualTo(ToolQueryResult.UNAVAILABLE);
        assertThat(tools.consultarEstadoEnvioPorTracking("LT-400").outcome()).isEqualTo(ToolQueryResult.INVALID_INPUT);
    }

    @Test void onlyTwoReadOnlyToolsAndReadOnlyFeignMethodsAreExposed() throws Exception {
        List<String> toolNames = java.util.Arrays.stream(LogisticsTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .map(method -> method.getAnnotation(Tool.class).name().isBlank()
                        ? method.getName() : method.getAnnotation(Tool.class).name())
                .sorted()
                .toList();
        assertThat(toolNames).containsExactly("consultarEstadoEnvioPorTracking", "obtenerRepartidoresDisponibles");

        for (Class<?> client : List.of(ShipmentFeignClient.class, CourierFeignClient.class)) {
            for (var method : client.getDeclaredMethods()) {
                assertThat(method.isAnnotationPresent(GetMapping.class)).isTrue();
                assertThat(method.isAnnotationPresent(PostMapping.class)).isFalse();
                assertThat(method.isAnnotationPresent(PutMapping.class)).isFalse();
                assertThat(method.isAnnotationPresent(PatchMapping.class)).isFalse();
                assertThat(method.isAnnotationPresent(DeleteMapping.class)).isFalse();
            }
        }
    }
}
