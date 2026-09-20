package com.ekusys.exam.repository.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ekusys.exam.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("submission_answer")
public class SubmissionAnswer extends BaseEntity {

    private Long id;
    private Long submissionId;
    private Long questionId;
    private String answerText;
    private Boolean objectiveCorrect;
    private Integer objectiveScore;
    private Integer subjectiveScore;
    private Boolean finalAnswer;
    private String source;
}
