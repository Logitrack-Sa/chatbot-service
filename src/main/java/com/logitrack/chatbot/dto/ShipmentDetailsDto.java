package com.logitrack.chatbot.dto;

import java.util.UUID;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.JsonNode;
public record ShipmentDetailsDto(UUID id, String trackingCode, String status, JsonNode origin,
                                 JsonNode destination, BigDecimal weightKg, UUID courierId) {}
