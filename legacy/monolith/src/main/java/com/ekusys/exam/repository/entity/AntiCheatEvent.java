package com.ekusys.exam.repository.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ekusys.exam.common.model.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("anti_cheat_event")
public class AntiCheatEvent extends BaseEntity {

    private Long id;
    private Long examId;
    private Long studentId;
    private String eventType;
    private LocalDateTime eventTime;
    private Long durationMs;
    private String payload;
    private String evidenceJson;
}
