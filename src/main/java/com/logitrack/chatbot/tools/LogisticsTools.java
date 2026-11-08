package com.logitrack.chatbot.tools;

import com.logitrack.chatbot.client.CourierFeignClient;
import com.logitrack.chatbot.client.ShipmentFeignClient;
import com.logitrack.chatbot.dto.CourierSummaryDto;
import com.logitrack.chatbot.dto.PageResponse;
import com.logitrack.chatbot.dto.ShipmentDetailsDto;
import com.logitrack.chatbot.dto.ToolQueryResult;
import feign.FeignException;
import feign.RetryableException;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import static com.logitrack.chatbot.dto.ToolQueryResult.ACCESS_DENIED;
import static com.logitrack.chatbot.dto.ToolQueryResult.INVALID_INPUT;
import static com.logitrack.chatbot.dto.ToolQueryResult.NOT_FOUND;
import static com.logitrack.chatbot.dto.ToolQueryResult.SUCCESS;
import static com.logitrack.chatbot.dto.ToolQueryResult.UNAVAILABLE;

@Component
public class LogisticsTools {
    private final ShipmentFeignClient shipmentClient;
    private final CourierFeignClient courierClient;

    public LogisticsTools(ShipmentFeignClient shipmentClient, CourierFeignClient courierClient) {
        this.shipmentClient = shipmentClient;
        this.courierClient = courierClient;
    }

    @Tool(description = "Consulta el estado actual y los datos de un envío usando su código de seguimiento único. "
            + "Úsala cuando el usuario proporcione un código de seguimiento. Solo lectura.")
    public ToolQueryResult<ShipmentDetailsDto> consultarEstadoEnvioPorTracking(
            @ToolParam(description = "Código de seguimiento único del envío") String trackingCode) {
        if (trackingCode == null || trackingCode.isBlank()) {
            return new ToolQueryResult<>(INVALID_INPUT, null, "El código de seguimiento es obligatorio.");
        }
        try {
            ShipmentDetailsDto shipment = shipmentClient.findByTrackingCode(trackingCode.trim());
            if (shipment == null) {
                return new ToolQueryResult<>(UNAVAILABLE, null,
                        "El servicio de envíos devolvió una respuesta inválida. Intenta nuevamente más tarde.");
            }
            return new ToolQueryResult<>(SUCCESS, shipment, "Envío encontrado.");
        } catch (FeignException.NotFound ex) {
            return new ToolQueryResult<>(NOT_FOUND, null, "No existe un envío con ese código de seguimiento.");
        } catch (FeignException ex) {
            return failure(ex, "envíos", "No existe un envío con ese código de seguimiento.");
        }
    }

    @Tool(description = "Consulta como máximo los primeros diez repartidores disponibles en este momento. "
            + "Úsala cuando el usuario pregunte qué repartidores están disponibles. Solo lectura.")
    public ToolQueryResult<List<CourierSummaryDto>> obtenerRepartidoresDisponibles() {
        try {
            PageResponse<CourierSummaryDto> page = courierClient.findCouriersByStatus("AVAILABLE", 0, 10);
            if (page == null || page.content() == null || page.content().stream().anyMatch(Objects::isNull)) {
                return new ToolQueryResult<>(UNAVAILABLE, null,
                        "El servicio de repartidores devolvió una respuesta inválida. Intenta nuevamente más tarde.");
            }
            List<CourierSummaryDto> couriers = List.copyOf(page.content());
            if (couriers.isEmpty()) {
                return new ToolQueryResult<>(SUCCESS, couriers, "No hay repartidores disponibles en este momento.");
            }
            return new ToolQueryResult<>(SUCCESS, couriers,
                    "Se encontraron repartidores disponibles. La lista muestra como máximo los primeros diez.");
        } catch (FeignException.BadRequest ex) {
            return new ToolQueryResult<>(UNAVAILABLE, null,
                    "El servicio de repartidores no acepta el estado AVAILABLE; su contrato es incompatible "
                            + "con esta consulta. Intenta nuevamente más tarde.");
        } catch (FeignException ex) {
            return failure(ex, "repartidores", "No se encontró el listado de repartidores solicitado.");
        }
    }

    private static <T> ToolQueryResult<T> failure(FeignException ex, String resource, String notFoundMessage) {
        int status = ex.status();
        if (status == 401 || status == 403) {
            return new ToolQueryResult<>(ACCESS_DENIED, null,
                    "No tienes permisos para consultar " + resource + ".");
        }
        if (status == 404) {
            return new ToolQueryResult<>(NOT_FOUND, null, notFoundMessage);
        }
        if (status == 408 || status == 429 || status >= 500 || status <= 0
                || (status >= 200 && status < 400) || ex instanceof RetryableException) {
            return new ToolQueryResult<>(UNAVAILABLE, null,
                    "El servicio de " + resource + " no está disponible temporalmente. Intenta nuevamente más tarde.");
        }
        return new ToolQueryResult<>(INVALID_INPUT, null,
                "La consulta de " + resource + " no pudo procesarse correctamente.");
    }
}
