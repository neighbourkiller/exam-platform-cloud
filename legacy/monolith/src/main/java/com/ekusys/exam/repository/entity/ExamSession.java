package com.ekusys.exam.repository.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ekusys.exam.common.model.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_session")
public class ExamSession extends BaseEntity {

    private Long id;
    private Long examId;
    private Long studentId;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime deadlineTime;
    private LocalDateTime endTime;
    private LocalDateTime lastSnapshotTime;
}
