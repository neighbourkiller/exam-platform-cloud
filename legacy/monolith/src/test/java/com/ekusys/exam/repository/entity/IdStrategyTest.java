package com.ekusys.exam.repository.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class IdStrategyTest {

    @Test
    void questionShouldUseAssignId() throws Exception {
        Field idField = Question.class.getDeclaredField("id");
        TableId tableId = idField.getAnnotation(TableId.class);
        assertNotNull(tableId);
        assertEquals(IdType.ASSIGN_ID, tableId.type());
    }

    @Test
    void questionAssetShouldUseAssignId() throws Exception {
        Field idField = QuestionAsset.class.getDeclaredField("id");
        TableId tableId = idField.getAnnotation(TableId.class);
        assertNotNull(tableId);
        assertEquals(IdType.ASSIGN_ID, tableId.type());
    }
}

