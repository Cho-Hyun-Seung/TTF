package com.toki.ttf.domain.room.common;

import java.time.Instant;

public record ApiResponse<T>(
        String status,
        T data,
        Instant timestamp
) {

    private static final String SUCCESS_STATUS = "SUCCESS";

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(SUCCESS_STATUS, data, Instant.now());
    }

    public static <T> ApiResponse<T> error(T data) {
        return new ApiResponse<>("ERROR", data, Instant.now());
    }

}
