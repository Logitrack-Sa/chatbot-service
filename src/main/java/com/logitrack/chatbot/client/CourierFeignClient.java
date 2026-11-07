package com.logitrack.chatbot.client;

import com.logitrack.chatbot.dto.CourierSummaryDto;
import com.logitrack.chatbot.dto.PageResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "courier-service", url = "${logitrack.clients.courier-url:}", configuration = FeignClientSecurityConfig.class)
public interface CourierFeignClient {
    @GetMapping("/api/v1/couriers")
    PageResponse<CourierSummaryDto> findCouriersByStatus(@RequestParam("status") String status,
            @RequestParam("page") int page, @RequestParam("size") int size);
}
