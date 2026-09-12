package com.ekusys.exam.content.api;

public record QuestionCorrectionView(Long questionId, String type, String content, String optionsJson,
                                     String answer, String fingerprint, Long creatorId) {}
