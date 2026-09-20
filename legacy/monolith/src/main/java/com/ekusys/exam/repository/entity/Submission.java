package com.ekusys.exam.repository.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ekusys.exam.common.model.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("submission")
public class Submission extends BaseEntity {

    private Long id;
    private Long examId;
    private Long studentId;
    private String status;
    private Integer objectiveScore;
    private Integer subjectiveScore;
    private Integer totalScore;
    private Boolean passFlag;
    private LocalDateTime submittedAt;
}
