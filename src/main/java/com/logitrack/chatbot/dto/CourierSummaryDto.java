package com.logitrack.chatbot.dto;

import java.util.UUID;
public record CourierSummaryDto(UUID id, String name, String vehicleType, String status) {}
