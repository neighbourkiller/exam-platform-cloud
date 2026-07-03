package com.ekusys.exam.runtime.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.exam.dto.AntiCheatEventRequest;
import com.ekusys.exam.exam.dto.AntiCheatEvidenceUploadView;
import com.ekusys.exam.exam.dto.ProctoringDispositionRequest;
import com.ekusys.exam.exam.dto.ProctoringDispositionView;
import com.ekusys.exam.exam.dto.ProctoringOverviewView;
import com.ekusys.exam.exam.dto.ProctoringStudentTimelineView;
import com.ekusys.exam.exam.dto.ProctoringStudentView;
import com.ekusys.exam.exam.dto.SnapshotAckView;
import com.ekusys.exam.exam.dto.SnapshotRequest;
import com.ekusys.exam.exam.dto.StartExamResponse;
import com.ekusys.exam.exam.dto.StudentExamView;
import com.ekusys.exam.exam.dto.SubmitExamRequest;
import com.ekusys.exam.exam.dto.SubmitResultView;
import com.ekusys.exam.runtime.service.AntiCheatEvidenceService;
import com.ekusys.exam.runtime.service.ExamRuntimeService;
import com.ekusys.exam.runtime.service.ProctoringService;
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
public class RuntimeExamController {
    private final ExamRuntimeService runtime;
    private final ProctoringService proctoring;
    private final AntiCheatEvidenceService evidence;

    public RuntimeExamController(ExamRuntimeService runtime, ProctoringService proctoring,
                                 AntiCheatEvidenceService evidence) {
        this.runtime = runtime;
        this.proctoring = proctoring;
        this.evidence = evidence;
    }

    @GetMapping("/student")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<List<StudentExamView>> student() { return ApiResponse.ok(runtime.listStudent()); }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<StartExamResponse> start(@PathVariable Long id) { return ApiResponse.ok(runtime.start(id)); }

    @PostMapping("/{id}/snapshot")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<SnapshotAckView> snapshot(@PathVariable Long id, @Valid @RequestBody SnapshotRequest request) {
        return ApiResponse.ok("快照已保存", runtime.snapshot(id, request));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<SubmitResultView> submit(@PathVariable Long id, @Valid @RequestBody SubmitExamRequest request) {
        return ApiResponse.ok("交卷成功", runtime.submit(id, request));
    }

    @PostMapping("/{id}/anti-cheat-events")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<Void> antiCheat(@PathVariable Long id, @Valid @RequestBody AntiCheatEventRequest request) {
        runtime.antiCheat(id, request); return ApiResponse.ok("记录成功", null);
    }

    @PostMapping(value = "/{id}/anti-cheat-evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<AntiCheatEvidenceUploadView> evidence(@PathVariable Long id,
                                                             @RequestParam("file") MultipartFile file,
                                                             @RequestParam("source") String source,
                                                             @RequestParam("eventType") String eventType) {
        return ApiResponse.ok("证据上传成功", evidence.upload(id, file, source, eventType));
    }

    @GetMapping("/{id}/proctoring/overview")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProctoringOverviewView> overview(@PathVariable Long id) {
        return ApiResponse.ok(proctoring.overview(id));
    }

    @GetMapping("/{id}/proctoring/students")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<ProctoringStudentView>> proctoringStudents(@PathVariable Long id) {
        return ApiResponse.ok(proctoring.students(id));
    }

    @GetMapping("/{id}/proctoring/students/{studentId}/timeline")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProctoringStudentTimelineView> timeline(@PathVariable Long id, @PathVariable Long studentId) {
        return ApiResponse.ok(proctoring.timeline(id, studentId));
    }

    @PutMapping("/{id}/proctoring/students/{studentId}/disposition")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProctoringDispositionView> disposition(@PathVariable Long id, @PathVariable Long studentId,
                                                               @Valid @RequestBody ProctoringDispositionRequest request) {
        return ApiResponse.ok("处置记录已保存", proctoring.updateDisposition(id, studentId, request));
    }
}
