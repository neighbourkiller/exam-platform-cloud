package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;
import java.util.List;

public record PaperDeliveryView(
    Long examId,
    String examName,
    long paperSnapshotVersion,
    ProctoringPolicyView proctoringPolicy,
    List<PaperQuestionView> questions,
    List<PaperDraftAnswerView> draftAnswers,
    long draftVersion,
    LocalDateTime draftUpdatedAt
) {
}
