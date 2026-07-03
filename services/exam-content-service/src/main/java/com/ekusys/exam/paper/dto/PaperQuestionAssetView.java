package com.ekusys.exam.paper.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaperQuestionAssetView {

    private String assetId;
    private String url;
    private String fileType;
    private String originalName;
    private Long size;
}

