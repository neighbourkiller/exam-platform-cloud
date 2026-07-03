package com.ekusys.exam.academic.api;

import java.util.List;

public record ClassRosterView(Long classId, String className, Long subjectId, Long teacherId,
                              List<Long> studentIds, long version) {
}
