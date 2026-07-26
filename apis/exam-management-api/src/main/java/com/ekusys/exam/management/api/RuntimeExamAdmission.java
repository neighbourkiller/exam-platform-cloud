package com.ekusys.exam.management.api;

import com.ekusys.exam.content.api.PaperSnapshotView;

public record RuntimeExamAdmission(RuntimeExamMetadata metadata, String status,
                                   PaperSnapshotView paper) {
}
