package com.ekusys.exam.question.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuestionView {

    private String id;
    private Long subjectId;
    private String subjectName;
    private String type;
    private String difficulty;
    private String content;
    private String optionsJson;
    private String answer;
    private String analysis;
    private Integer defaultScore;
    private Long creatorId;
    private Boolean canManage;
    private List<QuestionImageUploadView> assets;
}
