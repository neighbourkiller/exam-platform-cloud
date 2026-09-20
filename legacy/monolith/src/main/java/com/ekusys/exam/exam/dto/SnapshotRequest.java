package com.ekusys.exam.exam.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;

@Data
public class SnapshotRequest {

    @NotNull
    private List<AnswerPayload> answers;

    private Long clientTimestamp;

    private Long snapshotVersion;
}
