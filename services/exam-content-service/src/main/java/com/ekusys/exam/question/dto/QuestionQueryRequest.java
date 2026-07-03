package com.ekusys.exam.question.dto;

import com.ekusys.exam.common.enums.Difficulty;
import com.ekusys.exam.common.enums.QuestionType;
import lombok.Data;

@Data
public class QuestionQueryRequest {

    private long pageNum = 1;
    private long pageSize = 10;
    private Long subjectId;
    private QuestionType type;
    private Difficulty difficulty;
    private String keyword;
}
