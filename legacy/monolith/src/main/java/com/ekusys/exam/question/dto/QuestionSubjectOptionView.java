package com.ekusys.exam.question.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuestionSubjectOptionView {

    private Long id;
    private String name;
}
