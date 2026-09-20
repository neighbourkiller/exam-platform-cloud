package com.ekusys.exam.paper.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaperDetailView {

    private String id;
    private String name;
    private String subjectId;
    private String subjectName;
    private String description;
    private Integer totalScore;
    private String teacherId;
    private List<PaperQuestionView> questions;
}
