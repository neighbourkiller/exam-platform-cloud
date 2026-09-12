package com.ekusys.exam.exam.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TeacherExamViewTest {
    @Test void browserRegradingReceivesLosslessExamIdWithoutRemovingLegacyField() {
        long id = 2099999999999999999L;
        var json = new ObjectMapper().valueToTree(TeacherExamView.builder().examId(id).build());
        assertEquals(Long.toString(id), json.path("examIdText").asText());
        assertEquals(id, json.path("examId").asLong());
    }
}
