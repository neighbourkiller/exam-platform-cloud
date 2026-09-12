package com.ekusys.exam.grading.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.content.api.PaperSnapshotQuestion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AnswerKeyValidationTest {
    final AnswerKeyService keys=new AnswerKeyService(mock(JdbcTemplate.class),new ObjectMapper());
    PaperSnapshotQuestion q(String type) { return new PaperSnapshotQuestion(1L,type,"EASY","题目",
        "[{\"label\":\"A\",\"value\":\"甲\"},{\"label\":\"B\",\"value\":\"乙\"}]","A","",10,1); }
    @Test void validatesAllObjectiveTypesWithoutIntroducingPartialCreditOrAlternativeAnswers() {
        assertEquals("A",keys.validate(q("SINGLE")," a "));
        assertEquals("A,B",keys.validate(q("MULTI"),"b,a"));
        assertEquals("B",keys.validate(q("JUDGE"),"B"));
        assertEquals("Answer",keys.validate(q("BLANK")," Answer "));
        for(String value:new String[]{"", "Z", "A,B", "A,A", "A,"}) assertThrows(BusinessException.class,() -> keys.validate(q("SINGLE"),value));
        for(String value:new String[]{"A,A", "A,", "A,Z"}) assertThrows(BusinessException.class,() -> keys.validate(q("MULTI"),value));
        assertThrows(BusinessException.class,() -> keys.validate(q("SHORT"),"B"));
    }
}
