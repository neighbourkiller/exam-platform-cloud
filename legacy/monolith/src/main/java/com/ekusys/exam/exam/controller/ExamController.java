package com.ekusys.exam.exam.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.audit.AuditOperation;
import com.ekusys.exam.exam.dto.AntiCheatEvidenceUploadView;
import com.ekusys.exam.exam.dto.AntiCheatEventRequest;
import com.ekusys.exam.exam.dto.ExamCreateRequest;
import com.ekusys.exam.exam.dto.ProctoringDispositionRequest;
import com.ekusys.exam.exam.dto.ProctoringDispositionView;
import com.ekusys.exam.exam.dto.ProctoringOverviewView;
import com.ekusys.exam.exam.dto.ProctoringStudentTimelineView;
import com.ekusys.exam.exam.dto.ProctoringStudentView;
import com.ekusys.exam.exam.dto.SnapshotAckView;
import com.ekusys.exam.exam.dto.SnapshotRequest;
import com.ekusys.exam.exam.dto.StartExamResponse;
import com.ekusys.exam.exam.dto.StudentExamResultView;
import com.ekusys.exam.exam.dto.StudentExamView;
import com.ekusys.exam.exam.dto.TeacherExamView;
import com.ekusys.exam.exam.dto.TeachingClassOptionView;
import com.ekusys.exam.exam.dto.SubmitExamRequest;
import com.ekusys.exam.exam.dto.SubmitResultView;
import com.ekusys.exam.exam.service.ExamAntiCheatEvidenceService;
import com.ekusys.exam.exam.service.ExamService;
import com.ekusys.exam.exam.service.ExamProctoringService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/exams")
public class ExamController {

    private final ExamService examService;
    private final ExamProctoringService examProctoringService;
    private final ExamAntiCheatEvidenceService examAntiCheatEvidenceService;

    public ExamController(ExamService examService,
                          ExamProctoringService examProctoringService,
                          ExamAntiCheatEvidenceService examAntiCheatEvidenceService) {
        this.examService = examService;
        this.examProctoringService = examProctoringService;
        this.examAntiCheatEvidenceService = examAntiCheatEvidenceService;
    }

    /**
     * 创建考试
     *
     * @param request 考试创建请求，包含考试名称、时间、班级等信息
     * @return 新创建考试的ID
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    @AuditOperation(action = "EXAM_CREATE", targetType = "EXAM", targetId = "#result.data", detail = "#request.name")
    public ApiResponse<Long> createExam(@Valid @RequestBody ExamCreateRequest request) {
        return ApiResponse.ok("创建成功", examService.createExam(request));
    }

    /**
     * 发布考试
     *
     * @param examId 考试ID
     * @return 操作结果
     */
    @PostMapping("/{examId}/publish")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    @AuditOperation(action = "EXAM_PUBLISH", targetType = "EXAM", targetId = "#examId")
    public ApiResponse<Void> publish(@PathVariable Long examId) {
        examService.publishExam(examId);
        return ApiResponse.ok("发布成功", null);
    }

    /**
     * 终止考试
     *
     * @param examId 考试ID
     * @return 操作结果
     */
    @PostMapping("/{examId}/terminate")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    @AuditOperation(action = "EXAM_TERMINATE", targetType = "EXAM", targetId = "#examId")
    public ApiResponse<Void> terminate(@PathVariable Long examId) {
        examService.terminateExam(examId);
        return ApiResponse.ok("终止成功", null);
    }

    /**
     * 获取学生参加的考试列表
     *
     * @return 学生参加的考试视图列表
     */
    @GetMapping("/student")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<List<StudentExamView>> studentExams() {
        return ApiResponse.ok(examService.listStudentExams());
    }

    /**
     * 获取学生考试成绩列表
     *
     * @return 学生考试结果视图列表
     */
    @GetMapping("/student/results")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<List<StudentExamResultView>> studentResults() {
        return ApiResponse.ok(examService.listStudentExamResults());
    }

    /**
     * 获取教师管理的考试列表
     *
     * @return 教师考试视图列表
     */
    @GetMapping("/teacher")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<TeacherExamView>> teacherExams() {
        return ApiResponse.ok(examService.listTeacherExams());
    }

    /**
     * 获取考试监考概览信息
     *
     * @param examId 考试ID
     * @return 监考概览视图，包含统计信息和异常情况
     */
    @GetMapping("/{examId}/proctoring/overview")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProctoringOverviewView> proctoringOverview(@PathVariable Long examId) {
        return ApiResponse.ok(examProctoringService.getOverview(examId));
    }

    /**
     * 获取考试监考学生列表
     *
     * @param examId 考试ID
     * @return 监考学生视图列表，包含学生状态信息
     */
    @GetMapping("/{examId}/proctoring/students")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<ProctoringStudentView>> proctoringStudents(@PathVariable Long examId) {
        return ApiResponse.ok(examProctoringService.listStudents(examId));
    }

    /**
     * 获取学生考试时间线
     *
     * @param examId 考试ID
     * @param studentId 学生ID
     * @return 学生考试时间线视图，包含操作记录和时间信息
     */
    @GetMapping("/{examId}/proctoring/students/{studentId}/timeline")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProctoringStudentTimelineView> proctoringTimeline(@PathVariable Long examId,
                                                                         @PathVariable Long studentId) {
        return ApiResponse.ok(examProctoringService.getStudentTimeline(examId, studentId));
    }

    /**
     * 更新监考处置记录
     *
     * @param examId 考试ID
     * @param studentId 学生ID
     * @param request 处置请求，包含处置状态和说明
     * @return 更新后的处置记录视图
     */
    @PutMapping("/{examId}/proctoring/students/{studentId}/disposition")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    @AuditOperation(action = "PROCTORING_DISPOSITION_UPDATE", targetType = "PROCTORING_DISPOSITION",
        targetId = "#examId + ':' + #studentId", detail = "#request.status")
    public ApiResponse<ProctoringDispositionView> updateProctoringDisposition(@PathVariable Long examId,
                                                                              @PathVariable Long studentId,
                                                                              @Valid @RequestBody ProctoringDispositionRequest request) {
        return ApiResponse.ok("处置记录已保存", examProctoringService.updateStudentDisposition(examId, studentId, request));
    }

    /**
     * 获取教师授课班级列表
     *
     * @return 教学班选项视图列表
     */
    @GetMapping("/teaching-classes")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<TeachingClassOptionView>> teachingClasses() {
        return ApiResponse.ok(examService.listTeachingClasses());
    }

    /**
     * 学生开始考试
     *
     * @param examId 考试ID
     * @return 开始考试响应，包含试卷信息和时间
     */
    @PostMapping("/{examId}/start")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<StartExamResponse> start(@PathVariable Long examId) {
        return ApiResponse.ok(examService.startExam(examId));
    }

    /**
     * 保存考试快照（自动保存功能）
     *
     * @param examId 考试ID
     * @param request 快照请求，包含学生答案
     * @return 快照保存确认
     */
    @PostMapping("/{examId}/snapshot")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<SnapshotAckView> snapshot(@PathVariable Long examId, @Valid @RequestBody SnapshotRequest request) {
        return ApiResponse.ok("快照已保存", examService.saveSnapshot(examId, request));
    }

    /**
     * 记录作弊事件
     *
     * @param examId 考试ID
     * @param request 作弊事件请求，包含事件类型和详情
     * @return 操作结果
     */
    @PostMapping("/{examId}/anti-cheat-events")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<Void> antiCheat(@PathVariable Long examId, @Valid @RequestBody AntiCheatEventRequest request) {
        examService.recordAntiCheatEvent(examId, request);
        return ApiResponse.ok("记录成功", null);
    }

    /**
     * 上传作弊证据文件
     *
     * @param examId 考试ID
     * @param file 证据文件（图片/视频）
     * @param source 证据来源（如摄像头、屏幕录制等）
     * @param eventType 事件类型
     * @return 上传成功确认，包含文件URL
     */
    @PostMapping(value = "/{examId}/anti-cheat-evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<AntiCheatEvidenceUploadView> antiCheatEvidence(@PathVariable Long examId,
                                                                      @RequestParam("file") MultipartFile file,
                                                                      @RequestParam("source") String source,
                                                                      @RequestParam("eventType") String eventType) {
        return ApiResponse.ok("证据上传成功", examAntiCheatEvidenceService.upload(examId, file, source, eventType));
    }

    /**
     * 提交考试答卷
     *
     * @param examId 考试ID
     * @param request 提交请求，包含学生答案
     * @return 提交结果，包含得分和提交时间
     */
    @PostMapping("/{examId}/submit")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<SubmitResultView> submit(@PathVariable Long examId, @Valid @RequestBody SubmitExamRequest request) {
        return ApiResponse.ok("交卷成功", examService.submit(examId, request));
    }
}
