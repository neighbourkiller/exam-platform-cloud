package com.ekusys.exam.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ekusys.exam.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("question_asset")
public class QuestionAsset extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private Long questionId;
    private Long uploaderId;
    private String fileType;
    private String url;
    private String objectKey;
    private String originalName;
    private String contentType;
    private Long size;
}
