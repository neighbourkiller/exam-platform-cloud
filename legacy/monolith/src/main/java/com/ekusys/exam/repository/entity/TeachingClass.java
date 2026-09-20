package com.ekusys.exam.repository.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ekusys.exam.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("teaching_class")
public class TeachingClass extends BaseEntity {

    private Long id;
    private String name;
    private Long subjectId;
    private Long teacherId;
    private String term;
    private String status;
    private Integer capacity;
}

