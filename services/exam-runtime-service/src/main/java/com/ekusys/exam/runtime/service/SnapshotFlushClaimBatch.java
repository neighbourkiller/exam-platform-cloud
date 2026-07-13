package com.ekusys.exam.runtime.service;

import java.util.List;

record SnapshotFlushClaimBatch(List<SnapshotFlushClaim> claims, int recoveredLeases) {
}
