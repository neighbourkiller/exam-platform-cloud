package com.ekusys.exam.exam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PaperDeliveryRequest(
    @NotBlank @Size(max = 128) String clientId,
    @NotBlank @Size(max = 128) String leaseToken
) {
}
