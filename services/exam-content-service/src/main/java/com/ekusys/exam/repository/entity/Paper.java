package com.ekusys.exam.repository.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ekusys.exam.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("paper")
public class Paper extends BaseEntity {

    private Long id;
    private String name;
    private Long subjectId;
    private String description;
    private Integer totalScore;
    private Long teacherId;
}
