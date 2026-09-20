package com.ekusys.exam.repository.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ekusys.exam.common.model.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("subjective_grade")
public class SubjectiveGrade extends BaseEntity {

    private Long id;
    private Long submissionAnswerId;
    private Long teacherId;
    private Integer score;
    private String comment;
    private LocalDateTime gradedAt;
}
