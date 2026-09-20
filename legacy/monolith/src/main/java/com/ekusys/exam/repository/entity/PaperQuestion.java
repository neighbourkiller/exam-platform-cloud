package com.ekusys.exam.repository.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ekusys.exam.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("paper_question")
public class PaperQuestion extends BaseEntity {

    private Long id;
    private Long paperId;
    private Long questionId;
    private Integer score;
    private Integer sortOrder;
}
