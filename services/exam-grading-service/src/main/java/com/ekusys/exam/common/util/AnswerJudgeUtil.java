package com.ekusys.exam.common.util;

import com.ekusys.exam.common.enums.QuestionType;
import java.util.Arrays;
import java.util.stream.Collectors;

public final class AnswerJudgeUtil {

    private AnswerJudgeUtil() {
    }

    public static boolean isObjectiveType(String type) {
        return QuestionType.SINGLE.name().equals(type)
            || QuestionType.MULTI.name().equals(type)
            || QuestionType.JUDGE.name().equals(type)
            || QuestionType.BLANK.name().equals(type);
    }

    public static boolean isCorrect(String type, String standard, String answer) {
        if (standard == null) {
            return false;
        }
        if (answer == null) {
            return false;
        }
        if (QuestionType.MULTI.name().equals(type)) {
            return normalizeMulti(standard).equals(normalizeMulti(answer));
        }
        return standard.trim().equalsIgnoreCase(answer.trim());
    }

    private static String normalizeMulti(String value) {
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(v -> !v.isBlank())
            .map(String::toUpperCase)
            .sorted()
            .collect(Collectors.joining(","));
    }
}
