package com.ekusys.exam.paper.dto;

import lombok.Data;

@Data
public class PaperQueryRequest {

    private long pageNum = 1;
    private long pageSize = 10;
    private Long subjectId;
    private String name;
    private Long creatorId;
}

