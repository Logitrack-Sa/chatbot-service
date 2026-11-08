package com.logitrack.chatbot.dto;

public record ToolQueryResult<T>(String outcome, T data, String message) {
    public static final String SUCCESS = "SUCCESS";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String INVALID_INPUT = "INVALID_INPUT";
}
