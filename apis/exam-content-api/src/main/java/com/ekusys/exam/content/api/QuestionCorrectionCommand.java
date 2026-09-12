package com.ekusys.exam.content.api;

public record QuestionCorrectionCommand(String operationId, Long operatorId, boolean administrator,
                                        String expectedFingerprint, String answer) {}
