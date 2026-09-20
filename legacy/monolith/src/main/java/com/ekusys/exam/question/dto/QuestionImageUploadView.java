package com.ekusys.exam.question.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuestionImageUploadView {

    private String assetId;
    private String url;
    private String objectKey;
    private String originalName;
    private Long size;
    private String fileType;
}
