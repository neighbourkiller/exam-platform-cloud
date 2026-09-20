package com.ekusys.exam.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ekusys.exam.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("question")
public class Question extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private Long subjectId;
    private String type;
    private String difficulty;
    private String content;
    private String optionsJson;
    private String answer;
    private String analysis;
    private Integer defaultScore;
    private Long creatorId;
}
