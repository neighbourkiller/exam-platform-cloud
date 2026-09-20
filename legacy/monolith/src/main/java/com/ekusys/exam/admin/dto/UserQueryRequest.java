package com.ekusys.exam.admin.dto;

import lombok.Data;

@Data
public class UserQueryRequest {

    private long pageNum = 1;
    private long pageSize = 10;
    private String keyword;
    private String roleCode;
}
