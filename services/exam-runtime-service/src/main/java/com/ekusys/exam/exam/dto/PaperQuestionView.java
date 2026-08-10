package com.ekusys.exam.exam.dto;

import com.ekusys.exam.question.dto.QuestionImageUploadView;
import java.util.List;

public record PaperQuestionView(
    Long questionId,
    String type,
    String content,
    String optionsJson,
    int score,
    int sortOrder,
    List<QuestionImageUploadView> assets
) {
}
