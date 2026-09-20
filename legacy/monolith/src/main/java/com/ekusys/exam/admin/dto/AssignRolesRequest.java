package com.ekusys.exam.admin.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

@Data
public class AssignRolesRequest {

    @NotEmpty
    private List<Long> roleIds;
}
