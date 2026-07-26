package com.ekusys.exam.content.api;

import java.util.List;

public record PaperSnapshotView(Long snapshotId, Long paperId, long version, String name,
                                Long subjectId, int totalScore, List<PaperSnapshotQuestion> questions) {
    public PaperSnapshotView deliveryView() {
        return new PaperSnapshotView(
            snapshotId, paperId, version, name, subjectId, totalScore,
            questions.stream().map(PaperSnapshotQuestion::deliveryView).toList()
        );
    }
}
