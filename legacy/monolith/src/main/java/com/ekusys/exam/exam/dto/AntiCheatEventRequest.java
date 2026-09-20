package com.ekusys.exam.exam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AntiCheatEventRequest {

    @NotBlank
    private String eventType;

    @NotNull
    private Long durationMs;

    private String payload;

    private String evidenceJson;
}
