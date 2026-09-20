package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProctoringDispositionView {

    private String status;
    private String remark;
    private Long handledBy;
    private String handledByName;
    private LocalDateTime handledAt;
}
