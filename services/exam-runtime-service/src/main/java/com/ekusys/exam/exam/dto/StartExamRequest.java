package com.ekusys.exam.exam.dto;

import lombok.Data;

@Data
public class StartExamRequest {

    private String clientId;

    private String leaseToken;
}
