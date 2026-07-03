package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;

public record ProctoringDispositionView(String status, String remark, Long handledBy,
                                        String handledByName, LocalDateTime handledAt) {
}
