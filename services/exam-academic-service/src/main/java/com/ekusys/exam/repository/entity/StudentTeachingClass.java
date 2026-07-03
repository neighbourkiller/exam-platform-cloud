package com.ekusys.exam.repository.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ekusys.exam.common.model.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("student_teaching_class")
public class StudentTeachingClass extends BaseEntity {

    private Long id;
    private Long studentId;
    private Long subjectId;
    private Long teachingClassId;
    private String enrollStatus;
    private LocalDateTime enrolledAt;
    private LocalDateTime droppedAt;
}

