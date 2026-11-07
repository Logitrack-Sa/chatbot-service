package com.logitrack.chatbot.client;

import com.logitrack.chatbot.dto.ShipmentDetailsDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "shipment-service", url = "${logitrack.clients.shipment-url:}", configuration = FeignClientSecurityConfig.class)
public interface ShipmentFeignClient {
    @GetMapping("/api/v1/shipments/tracking/{trackingCode}")
    ShipmentDetailsDto findByTrackingCode(@PathVariable("trackingCode") String trackingCode);
}
