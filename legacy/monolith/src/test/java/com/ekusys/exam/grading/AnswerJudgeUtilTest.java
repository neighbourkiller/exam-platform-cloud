package com.ekusys.exam.grading;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ekusys.exam.common.enums.QuestionType;
import com.ekusys.exam.common.util.AnswerJudgeUtil;
import org.junit.jupiter.api.Test;

class AnswerJudgeUtilTest {

    @Test
    void multiChoiceShouldIgnoreOrderAndCase() {
        boolean result = AnswerJudgeUtil.isCorrect(QuestionType.MULTI.name(), "A,B,C", "c,a,b");
        assertTrue(result);
    }

    @Test
    void singleChoiceShouldCompareTrimmedText() {
        boolean result = AnswerJudgeUtil.isCorrect(QuestionType.SINGLE.name(), "A", " A ");
        assertTrue(result);
    }

    @Test
    void wrongAnswerShouldReturnFalse() {
        boolean result = AnswerJudgeUtil.isCorrect(QuestionType.JUDGE.name(), "true", "false");
        assertFalse(result);
    }
}
