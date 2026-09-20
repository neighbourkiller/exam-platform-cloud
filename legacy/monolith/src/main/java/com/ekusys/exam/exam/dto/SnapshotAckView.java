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
}
