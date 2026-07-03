package com.ekusys.exam.content.api;

import java.util.List;

public record PaperSnapshotQuestion(Long questionId, String type, String difficulty, String content,
                                    String optionsJson, String answer, String analysis, int score,
                                    int sortOrder, List<PaperSnapshotAsset> assets) {
    public PaperSnapshotQuestion(Long questionId, String type, String difficulty, String content,
                                 String optionsJson, String answer, String analysis, int score, int sortOrder) {
        this(questionId, type, difficulty, content, optionsJson, answer, analysis, score, sortOrder, List.of());
    }

    public PaperSnapshotQuestion deliveryView() {
        return new PaperSnapshotQuestion(questionId, type, difficulty, content, optionsJson,
            null, null, score, sortOrder, assets);
    }
}
