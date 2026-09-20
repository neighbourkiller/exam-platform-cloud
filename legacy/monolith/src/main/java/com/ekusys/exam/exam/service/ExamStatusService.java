package com.ekusys.exam.exam.service;

import com.ekusys.exam.common.enums.ExamStatus;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.mapper.ExamMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class ExamStatusService {

    private final ExamMapper examMapper;

    public ExamStatusService(ExamMapper examMapper) {
        this.examMapper = examMapper;
    }

    public void refreshExamStatusByTime(Exam exam, LocalDateTime now) {
        if (exam == null || now == null || exam.getStartTime() == null || exam.getEndTime() == null) {
            return;
        }
        String currentStatus = exam.getStatus();
        if (currentStatus == null) {
            return;
        }
        if (ExamStatus.DRAFT.name().equals(currentStatus)
            || ExamStatus.TERMINATED.name().equals(currentStatus)
            || ExamStatus.FINISHED.name().equals(currentStatus)) {
            return;
        }

        String nextStatus = currentStatus;
        if (!now.isBefore(exam.getEndTime())) {
            nextStatus = ExamStatus.FINISHED.name();
        } else if (!now.isBefore(exam.getStartTime())) {
            nextStatus = ExamStatus.ONGOING.name();
        } else {
            nextStatus = ExamStatus.PUBLISHED.name();
        }

        if (!Objects.equals(currentStatus, nextStatus)) {
            exam.setStatus(nextStatus);
            examMapper.updateById(exam);
        }
    }
}
