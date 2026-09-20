package com.ekusys.exam.paper.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaperListItemView {

    private String id;
    private String name;
    private String subjectId;
    private String subjectName;
    private Integer totalScore;
    private String teacherId;
    private LocalDateTime createTime;
    private Boolean canManage;
}
