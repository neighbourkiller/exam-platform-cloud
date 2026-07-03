package com.ekusys.exam.repository.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ekusys.exam.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("student_profile")
public class StudentProfile extends BaseEntity {

    private Long id;
    private Long userId;
    private String studentNo;
    private String enrollmentYear;
    private String status;
}

