package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SnapshotAckView {

    private LocalDateTime serverReceivedAt;
    private Long clientTimestamp;
    private Long snapshotVersion;
    private Boolean accepted;
    private Long serverRevision;
    private Long storedClientSequence;
    private Long serverEpochMs;
    private Long deadlineEpochMs;
    private String leaseToken;
    private LocalDateTime leaseExpiresAt;
    private Integer heartbeatIntervalSeconds;
    private Integer leaseTimeoutSeconds;
}
