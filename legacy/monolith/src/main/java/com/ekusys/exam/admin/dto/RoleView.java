package com.ekusys.exam.admin.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RoleView {

    private Long id;
    private String code;
    private String name;
}
