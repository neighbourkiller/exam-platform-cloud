package com.ekusys.exam.repository.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ekusys.exam.common.model.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam")
public class Exam extends BaseEntity {

    private Long id;
    private String name;
    private Long paperId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationMinutes;
    private Integer passScore;
    private String status;
    private Long publisherId;
    private String proctoringLevel;
    private String proctoringConfigJson;
}
