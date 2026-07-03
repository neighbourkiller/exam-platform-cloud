package com.ekusys.exam.paper.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaperQuestionView {

    private String questionId;
    private String type;
    private String difficulty;
    private String content;
    private String optionsJson;
    private String answer;
    private Integer score;
    private Integer sortOrder;
    private List<PaperQuestionAssetView> assets;
}
