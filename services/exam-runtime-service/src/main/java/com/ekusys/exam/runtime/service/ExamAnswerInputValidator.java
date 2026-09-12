package com.ekusys.exam.runtime.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.dto.AnswerPayload;
import com.ekusys.exam.exam.dto.ExamAnswerLimits;
import com.ekusys.exam.exam.dto.SnapshotRequest;
import com.ekusys.exam.exam.dto.SubmitExamRequest;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ExamAnswerInputValidator {
    public static final String INVALID_ANSWERS_CODE = "INVALID_EXAM_ANSWERS";
    public static final String INVALID_VERSION_CODE = "INVALID_SNAPSHOT_VERSION";

    public void validateAnswers(List<AnswerPayload> answers) {
        if (answers == null || answers.isEmpty() || answers.size() > ExamAnswerLimits.MAX_ANSWER_COUNT) {
            throw invalidAnswers("答案数量不正确");
        }
        Set<Long> questionIds = new HashSet<>();
        long totalLength = 0;
        for (AnswerPayload answer : answers) {
            if (answer == null || answer.getQuestionId() == null || answer.getQuestionId() <= 0) {
                throw invalidAnswers("存在无效题目ID");
            }
            if (!questionIds.add(answer.getQuestionId())) {
                throw invalidAnswers("答案中存在重复题目");
            }
            String answerText = answer.getAnswerText();
            if (answerText == null || answerText.length() > ExamAnswerLimits.MAX_ANSWER_TEXT_LENGTH) {
                throw invalidAnswers("单题答案长度超过限制");
            }
            totalLength += answerText.length();
            if (totalLength > ExamAnswerLimits.MAX_TOTAL_ANSWER_LENGTH) {
                throw invalidAnswers("答案总长度超过限制");
            }
        }
    }

    public void validateSubmitRequest(SubmitExamRequest request) {
        if (request == null) {
            throw new BusinessException("请求参数不正确");
        }
        validateAnswers(request.getAnswers());
        if (isBlankOrOversized(request.getClientId(), ExamAnswerLimits.MAX_CLIENT_ID_LENGTH)
            || isBlankOrOversized(request.getLeaseToken(), ExamAnswerLimits.MAX_LEASE_TOKEN_LENGTH)) {
            throw new BusinessException("请求参数不正确");
        }
    }

    public void validateSnapshotVersion(SnapshotRequest request, LocalDateTime receivedAt) {
        long serverTime = RuntimeTime.epochMillis(receivedAt);
        long maxVersion = serverTime + ExamAnswerLimits.MAX_FUTURE_SKEW_MILLIS;
        validateVersion(request.getClientTimestamp(), maxVersion);
        validateVersion(request.getSnapshotVersion(), maxVersion);
        validateVersion(request.getClientSequence(), maxVersion);
        if (request.getBaseServerRevision() != null && request.getBaseServerRevision() <= 0) {
            throw new BusinessException(INVALID_VERSION_CODE, "服务端草稿版本不正确");
        }
    }

    private void validateVersion(Long version, long maxVersion) {
        if (version == null) {
            return;
        }
        if (version <= 0 || version > maxVersion) {
            throw new BusinessException(
                INVALID_VERSION_CODE,
                "设备时间异常，请校准系统时间后重试"
            );
        }
    }

    private BusinessException invalidAnswers(String message) {
        return new BusinessException(INVALID_ANSWERS_CODE, message);
    }

    private boolean isBlankOrOversized(String value, int maxLength) {
        return value == null || value.isBlank() || value.length() > maxLength;
    }
}
