package com.ekusys.exam.auth.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MeResponse {

    private Long userId;
    private String username;
    private String realName;
    private String studentNo;
    private String enrollmentYear;
    private List<String> roles;
}
