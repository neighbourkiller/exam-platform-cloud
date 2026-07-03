package com.ekusys.exam.content.api;

import java.util.List;

public record PaperSnapshotView(Long snapshotId, Long paperId, long version, String name,
                                Long subjectId, int totalScore, List<PaperSnapshotQuestion> questions) {
}
