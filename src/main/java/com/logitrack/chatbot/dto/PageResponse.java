package com.logitrack.chatbot.dto;

import java.util.List;
public record PageResponse<T>(List<T> content, PageMetadata page) {
    public record PageMetadata(int size, int number, long totalElements, int totalPages) {}
}
