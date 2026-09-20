package com.ekusys.exam.exam.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AntiCheatEvidenceUploadView {

    private String url;
    private String objectKey;
    private String source;
    private String contentType;
    private Long size;
}
