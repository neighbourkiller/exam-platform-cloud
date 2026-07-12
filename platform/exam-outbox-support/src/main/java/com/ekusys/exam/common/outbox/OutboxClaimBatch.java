package com.ekusys.exam.common.outbox;

import java.util.List;

public record OutboxClaimBatch(List<OutboxRow> rows, int recoveredForRetry, int recoveredAsFailed) {
}
