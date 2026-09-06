package com.toki.ttf.domain.ttf.dto.request;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SaveStatementsRequest(
        @NotNull @Size(min = 3, max = 3)
        List<@Valid Statement> statements
) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Statement(
            @NotBlank String content,
            @NotNull Boolean isFake
    ) {}
}
