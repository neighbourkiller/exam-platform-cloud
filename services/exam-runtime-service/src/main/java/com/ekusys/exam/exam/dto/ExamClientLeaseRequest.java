package com.ekusys.exam.exam.dto;

import lombok.Data;

@Data
public class ExamClientLeaseRequest {

    private String clientId;

    private String leaseToken;
}
