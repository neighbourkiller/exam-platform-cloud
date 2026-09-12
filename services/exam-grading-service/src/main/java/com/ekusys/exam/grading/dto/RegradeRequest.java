package com.ekusys.exam.grading.dto;

import jakarta.validation.constraints.*;
import java.util.Map;

public record RegradeRequest(@NotBlank @Size(max=100) String requestKey,
                             @Min(0) long expectedVersion,
                             @NotBlank @Size(max=1000) String reason,
                             @NotNull @Size(max=1000) Map<@NotNull Long, @NotBlank @Size(max=10000) String> answers) {}
