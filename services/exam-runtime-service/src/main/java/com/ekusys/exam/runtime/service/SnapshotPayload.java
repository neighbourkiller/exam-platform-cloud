package com.ekusys.exam.runtime.service;

import com.ekusys.exam.exam.dto.AnswerPayload;
import java.util.List;

record SnapshotPayload(Long examId, Long studentId, List<AnswerPayload> answers,
                       Long clientTimestamp, long snapshotVersion,
                       String serverReceivedAt) {
}
