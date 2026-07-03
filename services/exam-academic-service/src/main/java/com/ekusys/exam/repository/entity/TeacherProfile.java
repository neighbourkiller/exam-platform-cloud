package com.ekusys.exam.repository.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ekusys.exam.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("teacher_profile")
public class TeacherProfile extends BaseEntity {

    private Long id;
    private Long userId;
    private String teacherNo;
    private String title;
    private String status;
}

