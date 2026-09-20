package com.ekusys.exam.exam.service;

import com.ekusys.exam.exam.dto.AntiCheatEventRequest;
import com.ekusys.exam.exam.dto.ExamCreateRequest;
import com.ekusys.exam.exam.dto.SnapshotRequest;
import com.ekusys.exam.exam.dto.SnapshotAckView;
import com.ekusys.exam.exam.dto.StartExamResponse;
import com.ekusys.exam.exam.dto.StudentExamResultView;
import com.ekusys.exam.exam.dto.StudentExamView;
import com.ekusys.exam.exam.dto.SubmitExamRequest;
import com.ekusys.exam.exam.dto.SubmitResultView;
import com.ekusys.exam.exam.dto.TeacherExamView;
import com.ekusys.exam.exam.dto.TeachingClassOptionView;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ExamService {

    private final ExamLifecycleService examLifecycleService;
    private final ExamTeacherQueryService examTeacherQueryService;
    private final ExamStudentQueryService examStudentQueryService;
    private final ExamStartService examStartService;
    private final ExamSnapshotService examSnapshotService;
    private final ExamAntiCheatWriteService examAntiCheatWriteService;
    private final ExamSubmissionService examSubmissionService;
    private final ExamAutoSubmitService examAutoSubmitService;

    public ExamService(ExamLifecycleService examLifecycleService,
                       ExamTeacherQueryService examTeacherQueryService,
                       ExamStudentQueryService examStudentQueryService,
                       ExamStartService examStartService,
                       ExamSnapshotService examSnapshotService,
                       ExamAntiCheatWriteService examAntiCheatWriteService,
                       ExamSubmissionService examSubmissionService,
                       ExamAutoSubmitService examAutoSubmitService) {
        this.examLifecycleService = examLifecycleService;
        this.examTeacherQueryService = examTeacherQueryService;
        this.examStudentQueryService = examStudentQueryService;
        this.examStartService = examStartService;
        this.examSnapshotService = examSnapshotService;
        this.examAntiCheatWriteService = examAntiCheatWriteService;
        this.examSubmissionService = examSubmissionService;
        this.examAutoSubmitService = examAutoSubmitService;
    }

    public Long createExam(ExamCreateRequest request) {
        return examLifecycleService.createExam(request);
    }

    public void publishExam(Long examId) {
        examLifecycleService.publishExam(examId);
    }

    public void terminateExam(Long examId) {
        examLifecycleService.terminateExam(examId);
    }

    public List<StudentExamView> listStudentExams() {
        return examStudentQueryService.listStudentExams();
    }

    public List<TeacherExamView> listTeacherExams() {
        return examTeacherQueryService.listTeacherExams();
    }

    public List<StudentExamResultView> listStudentExamResults() {
        return examStudentQueryService.listStudentExamResults();
    }

    public List<TeachingClassOptionView> listTeachingClasses() {
        return examTeacherQueryService.listTeachingClasses();
    }

    public StartExamResponse startExam(Long examId) {
        return examStartService.startExam(examId);
    }

    public SnapshotAckView saveSnapshot(Long examId, SnapshotRequest request) {
        return examSnapshotService.saveSnapshot(examId, request);
    }

    public void recordAntiCheatEvent(Long examId, AntiCheatEventRequest request) {
        examAntiCheatWriteService.record(examId, request);
    }

    public SubmitResultView submit(Long examId, SubmitExamRequest request) {
        return examSubmissionService.submit(examId, request);
    }

    public Map<Long, String> loadSnapshotAnswerMap(Long examId, Long studentId) {
        return examSnapshotService.loadSnapshotAnswerMap(examId, studentId);
    }

    public void flushAllSnapshotsToDatabase() {
        examSnapshotService.flushAllSnapshotsToDatabase();
    }

    public void flushSnapshotKey(String key) {
        examSnapshotService.flushSnapshotKey(key);
    }

    public void autoSubmitExpiredSessions() {
        examAutoSubmitService.autoSubmitExpiredSessions();
    }
}
