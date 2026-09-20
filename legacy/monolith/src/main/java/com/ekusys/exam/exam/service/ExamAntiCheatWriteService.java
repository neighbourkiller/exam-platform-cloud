package com.ekusys.exam.exam.service;

import com.ekusys.exam.exam.dto.AntiCheatEventRequest;
import com.ekusys.exam.exam.dto.ProctoringPolicyView;
import com.ekusys.exam.repository.entity.AntiCheatEvent;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.mapper.AntiCheatEventMapper;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamAntiCheatWriteService {

    private static final Logger log = LoggerFactory.getLogger(ExamAntiCheatWriteService.class);

    private final ExamAccessService examAccessService;
    private final AntiCheatEventMapper antiCheatEventMapper;
    private final ExamProctoringPolicyService proctoringPolicyService;

    public ExamAntiCheatWriteService(ExamAccessService examAccessService,
                                     AntiCheatEventMapper antiCheatEventMapper,
                                     ExamProctoringPolicyService proctoringPolicyService) {
        this.examAccessService = examAccessService;
        this.antiCheatEventMapper = antiCheatEventMapper;
        this.proctoringPolicyService = proctoringPolicyService;
    }

    @Transactional
    public void record(Long examId, AntiCheatEventRequest request) {
        Long studentId = examAccessService.getCurrentUserId();
        Exam exam = examAccessService.ensureExam(examId);
        examAccessService.checkStudentAccess(examId, studentId);

        String eventType = normalizeEventType(request.getEventType());
        ProctoringPolicyView policy = proctoringPolicyService.resolve(exam.getProctoringLevel(), exam.getProctoringConfigJson());
        if (!proctoringPolicyService.shouldRecordEvent(policy, eventType)) {
            return;
        }

        AntiCheatEvent event = new AntiCheatEvent();
        event.setExamId(examId);
        event.setStudentId(studentId);
        event.setEventType(eventType);
        event.setDurationMs(Math.max(0L, request.getDurationMs() == null ? 0L : request.getDurationMs()));
        event.setPayload(request.getPayload());
        event.setEvidenceJson(request.getEvidenceJson());
        event.setEventTime(LocalDateTime.now());
        antiCheatEventMapper.insert(event);
        log.info("Anti-cheat event recorded: examId={}, studentId={}, eventType={}",
            examId, studentId, eventType);
    }

    private String normalizeEventType(String eventType) {
        String normalized = eventType == null ? "" : eventType.trim().toUpperCase();
        if (normalized.isEmpty() || normalized.length() > 64) {
            return "UNKNOWN";
        }
        return normalized;
    }
}
