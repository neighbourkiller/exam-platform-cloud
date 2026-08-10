package com.ekusys.exam.runtime.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.content.api.PaperSnapshotQuestion;
import com.ekusys.exam.exam.dto.AnswerPayload;
import com.ekusys.exam.exam.dto.AntiCheatEventRequest;
import com.ekusys.exam.exam.dto.ExamClientLeaseRequest;
import com.ekusys.exam.exam.dto.ExamClientLeaseView;
import com.ekusys.exam.exam.dto.ProctoringPolicyView;
import com.ekusys.exam.exam.dto.SnapshotAckView;
import com.ekusys.exam.exam.dto.SnapshotRequest;
import com.ekusys.exam.exam.dto.StartExamRequest;
import com.ekusys.exam.exam.dto.StartExamResponse;
import com.ekusys.exam.exam.dto.StudentExamQuestionView;
import com.ekusys.exam.exam.dto.StudentExamView;
import com.ekusys.exam.exam.dto.SubmitExamRequest;
import com.ekusys.exam.exam.dto.SubmitResultView;
import com.ekusys.exam.exam.dto.SubmissionStatusView;
import com.ekusys.exam.management.api.RuntimeExamAdmission;
import com.ekusys.exam.management.api.RuntimeExamMetadata;
import com.ekusys.exam.runtime.api.GradingAnswerInput;
import com.ekusys.exam.runtime.api.GradingSubmissionInput;
import com.ekusys.exam.runtime.client.ManagementRuntimeClient;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository;
import com.ekusys.exam.runtime.service.ManualSubmissionService.ManualSubmissionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExamRuntimeService {
    private final JdbcTemplate jdbc;
    private final ManagementRuntimeClient management;
    private final ObjectMapper mapper;
    private final RuntimeOutboxService outbox;
    private final TimeoutSubmissionService timeoutSubmissionService;
    private final ExamSnapshotService snapshotService;
    private final ExamClientLeaseService clientLeaseService;
    private final ExamAnswerInputValidator answerInputValidator;
    private final TimeoutTaskRepository timeoutTasks;
    private final ManualSubmissionService manualSubmissionService;
    private final SubmissionFinalPayloadService finalPayloads;
    private final TransactionTemplate transactions;

    public ExamRuntimeService(JdbcTemplate jdbc, ManagementRuntimeClient management,
                              ObjectMapper mapper, RuntimeOutboxService outbox,
                              TimeoutSubmissionService timeoutSubmissionService,
                              ExamSnapshotService snapshotService,
                              ExamClientLeaseService clientLeaseService,
                              ExamAnswerInputValidator answerInputValidator,
                              TimeoutTaskRepository timeoutTasks,
                              ManualSubmissionService manualSubmissionService,
                              SubmissionFinalPayloadService finalPayloads,
                              TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.management = management;
        this.mapper = mapper;
        this.outbox = outbox;
        this.timeoutSubmissionService = timeoutSubmissionService;
        this.snapshotService = snapshotService;
        this.clientLeaseService = clientLeaseService;
        this.answerInputValidator = answerInputValidator;
        this.timeoutTasks = timeoutTasks;
        this.manualSubmissionService = manualSubmissionService;
        this.finalPayloads = finalPayloads;
        this.transactions = transactions;
    }

    public List<StudentExamView> listStudent() {
        Long userId = requireUser();
        Set<Long> submittedExamIds = submittedExamIds(userId);
        return management.summaries(userId).getData().stream()
            .map(snapshot -> StudentExamView.builder()
                .examId(snapshot.examId())
                .name(snapshot.name())
                .startTime(snapshot.startTime())
                .endTime(snapshot.endTime())
                .durationMinutes(snapshot.durationMinutes())
                .status(snapshot.status())
                .submitted(submittedExamIds.contains(snapshot.examId()))
                .proctoringLevel(snapshot.proctoringLevel())
                .proctoringPolicy(policy(snapshot.proctoringLevel(), snapshot.proctoringConfigJson()))
                .build())
            .toList();
    }

    @Transactional
    public StartExamResponse start(Long examId, StartExamRequest request) {
        Long userId = requireUser();
        RuntimeExamAdmission admission = management.admission(examId, userId).getData();
        RuntimeExamMetadata exam = admission.metadata();
        LocalDateTime now = dbNow();
        if (now.isBefore(exam.startTime()) || now.isAfter(exam.endTime())) {
            throw new BusinessException("当前不在考试时间内");
        }

        List<SessionRow> rows = findSessions(examId, userId);
        boolean resumed = !rows.isEmpty();
        SessionRow session;
        ExamClientLeaseView lease;
        if (resumed) {
            session = rows.getFirst();
            if (!"ANSWERING".equals(session.status())) {
                throw new BusinessException("你已提交过本场考试");
            }
            lease = clientLeaseService.acquire(
                examId, userId, session.id(), session.deadline(),
                clientId(request), leaseToken(request), now
            );
        } else {
            LocalDateTime durationDeadline = now.plusMinutes(exam.durationMinutes());
            LocalDateTime deadline = durationDeadline.isBefore(exam.endTime()) ? durationDeadline : exam.endTime();
            long sessionId = IdWorker.getId();
            lease = clientLeaseService.createInitialLease(clientId(request), now);
            try {
                jdbc.update(
                    """
                        insert into exam_session(
                            id,exam_id,student_id,status,start_time,deadline_time,claim_time,
                            active_client_id,active_client_token,active_client_lease_until,
                            active_client_last_seen,create_time,update_time
                        ) values(?,?,?,'ANSWERING',?,?,null,?,?,?,?,current_timestamp(3),current_timestamp(3))
                        """,
                    sessionId, examId, userId, now, deadline,
                    clientId(request), lease.getLeaseToken(), lease.getLeaseExpiresAt(), now
                );
                jdbc.update(
                    """
                        insert into submission(
                            id,exam_id,student_id,status,paper_snapshot_id,timeout_submit,create_time,update_time
                        ) values(?,?,?,'IN_PROGRESS',?,0,current_timestamp(3),current_timestamp(3))
                        """,
                    IdWorker.getId(), examId, userId, admission.paper().snapshotId()
                );
                outbox.sessionStarted(sessionId, examId, userId, now);
                session = new SessionRow(sessionId, now, deadline, "ANSWERING");
                clientLeaseService.activateAfterCommit(
                    examId, userId, sessionId, deadline, clientId(request), lease, now
                );
            } catch (DuplicateKeyException exception) {
                rows = findSessions(examId, userId);
                if (rows.isEmpty()) {
                    throw exception;
                }
                resumed = true;
                session = rows.getFirst();
                if (!"ANSWERING".equals(session.status())) {
                    throw new BusinessException("你已提交过本场考试");
                }
                lease = clientLeaseService.acquire(
                    examId, userId, session.id(), session.deadline(),
                    clientId(request), leaseToken(request), now
                );
            }
        }

        timeoutTasks.ensureTask(session.id(), examId, userId, session.deadline());

        SnapshotDraft draft = snapshotService.loadLatestDraft(examId, userId);
        List<StudentExamQuestionView> questions = admission.paper().questions().stream()
            .map(question -> question(question, draft.answers().get(question.questionId())))
            .toList();
        return StartExamResponse.builder()
            .examId(examId)
            .examName(exam.name())
            .resumed(resumed)
            .durationMinutes(exam.durationMinutes())
            .startTime(session.start())
            .endTime(exam.endTime())
            .deadlineTime(session.deadline())
            .draftUpdatedAt(draft.updatedAt())
            .leaseToken(lease.getLeaseToken())
            .leaseExpiresAt(lease.getLeaseExpiresAt())
            .heartbeatIntervalSeconds(lease.getHeartbeatIntervalSeconds())
            .leaseTimeoutSeconds(lease.getLeaseTimeoutSeconds())
            .proctoringPolicy(policy(exam.proctoringLevel(), exam.proctoringConfigJson()))
            .questions(questions)
            .build();
    }

    public ExamClientLeaseView heartbeat(Long examId, ExamClientLeaseRequest request) {
        Long userId = requireUser();
        LocalDateTime now = LocalDateTime.now();
        return clientLeaseService.renew(
            examId, userId, clientId(request), leaseToken(request), now, false
        ).lease();
    }

    public SnapshotAckView snapshot(Long examId, SnapshotRequest request) {
        Long userId = requireUser();
        LocalDateTime now = LocalDateTime.now();
        answerInputValidator.validateAnswers(request.getAnswers());
        answerInputValidator.validateSnapshotVersion(request, now);
        ExamClientLeaseContext leaseContext = clientLeaseService.renew(
            examId, userId, request.getClientId(), request.getLeaseToken(), now, true
        );
        ExamClientLeaseView lease = leaseContext.lease();
        SnapshotAckView ack = snapshotService.save(
            examId, userId, leaseContext.sessionId(), leaseContext.deadline(), now, request
        );
        ack.setLeaseToken(lease.getLeaseToken());
        ack.setLeaseExpiresAt(lease.getLeaseExpiresAt());
        ack.setHeartbeatIntervalSeconds(lease.getHeartbeatIntervalSeconds());
        ack.setLeaseTimeoutSeconds(lease.getLeaseTimeoutSeconds());
        return ack;
    }

    public SubmitResultView submit(Long examId, SubmitExamRequest request) {
        Long userId = requireUser();
        if (!timeoutSubmissionService.isV2Enabled()) {
            SubmitResultView result = transactions.execute(
                status -> submitLegacy(examId, userId, request)
            );
            if (result == null) {
                throw new IllegalStateException("交卷事务未返回结果");
            }
            return result;
        }

        SessionRow session = session(examId, userId);
        Long submissionId = submissionId(examId, userId);
        if ("SUBMITTED".equals(session.status())) {
            return submitResult(submissionId, "PROCESSING");
        }
        if ("AUTO_SUBMITTING".equals(session.status())) {
            return submitResult(submissionId, "SUBMITTING");
        }
        if (!"ANSWERING".equals(session.status())) {
            throw new BusinessException("会话已结束");
        }

        LocalDateTime now = dbNow();
        boolean expired = !now.isBefore(session.deadline());
        if (expired) {
            String status = timeoutSubmissionService.acceptExpired(
                session.id(), examId, userId, session.deadline()
            );
            return submitResult(submissionId, status);
        }
        answerInputValidator.validateSubmitRequest(request);
        clientLeaseService.requireCurrent(session.id(), request.getClientId(), request.getLeaseToken(), now);

        long draftVersion = submissionDraftVersion(submissionId);
        SubmissionFinalPayloadService.EncodedFinalAnswers encoded =
            finalPayloads.encode(request.getAnswers(), draftVersion);
        ManualSubmissionResult result = manualSubmissionService.submit(
            session.id(), examId, userId, session.deadline(), submissionId, request, encoded
        );
        if ("PROCESSING".equals(result.status())) {
            snapshotService.clearAfterCommit(examId, userId);
            clientLeaseService.clearAfterCommit(examId, userId, request.getLeaseToken());
        }
        return submitResult(submissionId, result.status());
    }

    public SubmissionStatusView submissionStatus(Long examId) {
        Long userId = requireUser();
        List<SubmissionStatusView> rows = jdbc.query(
            """
                select sub.id submission_id,s.status session_status,
                       sub.status submission_status,sub.timeout_submit,sub.submitted_at,
                       task.status timeout_task_status
                  from exam_session s
                  left join submission sub
                    on sub.exam_id=s.exam_id and sub.student_id=s.student_id
                  left join submission_timeout_task task on task.session_id=s.id
                 where s.exam_id=? and s.student_id=?
                 limit 1
                """,
            (rs, rowNum) -> SubmissionStatusView.builder()
                .submissionId(rs.getObject("submission_id", Long.class))
                .sessionStatus(rs.getString("session_status"))
                .submissionStatus(rs.getString("submission_status"))
                .timeoutTaskStatus(rs.getString("timeout_task_status"))
                .timeoutSubmit(rs.getObject("timeout_submit") == null
                    ? null : rs.getBoolean("timeout_submit"))
                .submittedAt(rs.getObject("submitted_at", LocalDateTime.class))
                .build(),
            examId, userId
        );
        if (rows.isEmpty()) {
            throw new BusinessException("考试会话不存在");
        }
        return rows.getFirst();
    }

    private SubmitResultView submitLegacy(Long examId, Long userId, SubmitExamRequest request) {
        SessionRow session = active(examId, userId);
        LocalDateTime now = dbNow();
        boolean expired = !now.isBefore(session.deadline());
        Long submissionId = submissionId(examId, userId);
        if (expired) {
            timeoutSubmissionService.submitExpired(session.id(), examId, userId);
            return submitResult(submissionId, "PROCESSING");
        }
        answerInputValidator.validateSubmitRequest(request);
        clientLeaseService.requireCurrent(session.id(), request.getClientId(), request.getLeaseToken(), now);

        saveAnswers(submissionId, request.getAnswers(), "SUBMIT", true);
        jdbc.update(
            """
                update submission
                   set status='PROCESSING',submitted_at=current_timestamp(3),timeout_submit=0,
                       update_time=current_timestamp(3)
                 where id=?
                """,
            submissionId
        );
        jdbc.update(
            """
                update exam_session
                   set status='SUBMITTED',end_time=current_timestamp(3),
                       active_client_id=null,active_client_token=null,
                       active_client_lease_until=null,active_client_last_seen=null,
                       update_time=current_timestamp(3)
                 where id=? and status='ANSWERING'
                """,
            session.id()
        );
        outbox.submissionAccepted(submissionId);
        snapshotService.clearAfterCommit(examId, userId);
        clientLeaseService.clearAfterCommit(examId, userId, request.getLeaseToken());
        return submitResult(submissionId, "PROCESSING");
    }

    @Transactional
    public void antiCheat(Long examId, AntiCheatEventRequest request) {
        Long userId = requireUser();
        active(examId, userId);
        long eventId = IdWorker.getId();
        jdbc.update(
            """
                insert into anti_cheat_event(
                    id,exam_id,student_id,event_type,event_time,duration_ms,payload,evidence_json,
                    create_time,update_time
                ) values(?,?,?,?,current_timestamp(3),?,?,?,current_timestamp(3),current_timestamp(3))
                """,
            eventId, examId, userId, request.getEventType(), request.getDurationMs(),
            request.getPayload(), request.getEvidenceJson()
        );
        outbox.proctoringEvent(eventId);
    }

    public GradingSubmissionInput gradingInput(Long submissionId) {
        List<GradingSubmissionInput> rows = jdbc.query(
            "select id,exam_id,student_id,paper_snapshot_id,submitted_at from submission where id=?",
            (rs, rowNum) -> new GradingSubmissionInput(
                rs.getLong("id"), rs.getLong("exam_id"), rs.getLong("student_id"), null, null,
                rs.getLong("paper_snapshot_id"), rs.getObject("submitted_at", LocalDateTime.class), List.of()
            ),
            submissionId
        );
        if (rows.isEmpty()) {
            throw new BusinessException("交卷记录不存在");
        }
        GradingSubmissionInput row = rows.getFirst();
        RuntimeExamMetadata exam = management.metadata(row.examId()).getData();
        List<GradingAnswerInput> answers = finalPayloads.hasPayload(submissionId)
            ? finalPayloads.loadForGrading(submissionId)
            : jdbc.query(
                "select id,question_id,answer_text from submission_answer where submission_id=?",
                (rs, rowNum) -> new GradingAnswerInput(
                    rs.getLong("id"), rs.getLong("question_id"), rs.getString("answer_text")
                ),
                submissionId
            );
        return new GradingSubmissionInput(
            row.submissionId(), row.examId(), row.studentId(), exam.name(), exam.passScore(),
            row.paperSnapshotId(), row.submittedAt(), answers
        );
    }

    private void saveAnswers(Long submissionId, List<AnswerPayload> answers,
                             String source, boolean finalAnswer) {
        jdbc.update("delete from submission_answer where submission_id=?", submissionId);
        for (AnswerPayload answer : answers) {
            jdbc.update(
                """
                    insert into submission_answer(
                        id,submission_id,question_id,answer_text,final_answer,source,create_time,update_time
                    ) values(?,?,?,?,?,?,current_timestamp(3),current_timestamp(3))
                    """,
                IdWorker.getId(), submissionId, answer.getQuestionId(), answer.getAnswerText(),
                finalAnswer ? 1 : 0, source
            );
        }
    }

    private List<SessionRow> findSessions(Long examId, Long userId) {
        return jdbc.query(
            "select id,start_time,deadline_time,status from exam_session where exam_id=? and student_id=?",
            (rs, rowNum) -> new SessionRow(
                rs.getLong("id"), rs.getObject("start_time", LocalDateTime.class),
                rs.getObject("deadline_time", LocalDateTime.class), rs.getString("status")
            ),
            examId, userId
        );
    }

    private SessionRow active(Long examId, Long userId) {
        SessionRow row = session(examId, userId);
        if (!"ANSWERING".equals(row.status())) {
            throw new BusinessException("会话已结束");
        }
        return row;
    }

    private SessionRow session(Long examId, Long userId) {
        List<SessionRow> rows = findSessions(examId, userId);
        if (rows.isEmpty()) {
            throw new BusinessException("考试会话不存在");
        }
        return rows.getFirst();
    }

    private Long submissionId(Long examId, Long userId) {
        return jdbc.queryForObject(
            "select id from submission where exam_id=? and student_id=?",
            Long.class, examId, userId
        );
    }

    private long submissionDraftVersion(Long submissionId) {
        Long version = jdbc.queryForObject(
            "select draft_version from submission where id=?",
            Long.class,
            submissionId
        );
        return version == null ? 0L : version;
    }

    private SubmitResultView submitResult(Long submissionId, String status) {
        return SubmitResultView.builder().submissionId(submissionId).status(status).build();
    }

    private String clientId(StartExamRequest request) {
        return request == null ? null : request.getClientId();
    }

    private String leaseToken(StartExamRequest request) {
        return request == null ? null : request.getLeaseToken();
    }

    private String clientId(ExamClientLeaseRequest request) {
        return request == null ? null : request.getClientId();
    }

    private String leaseToken(ExamClientLeaseRequest request) {
        return request == null ? null : request.getLeaseToken();
    }

    private Set<Long> submittedExamIds(Long userId) {
        return Set.copyOf(jdbc.queryForList(
            "select exam_id from submission where student_id=? and status<>'IN_PROGRESS'",
            Long.class,
            userId
        ));
    }

    private StudentExamQuestionView question(PaperSnapshotQuestion question, String answer) {
        return StudentExamQuestionView.builder()
            .questionId(question.questionId())
            .type(question.type())
            .content(question.content())
            .optionsJson(question.optionsJson())
            .score(question.score())
            .sortOrder(question.sortOrder())
            .currentAnswer(answer)
            .assets(question.assets().stream()
                .map(asset -> com.ekusys.exam.question.dto.QuestionImageUploadView.builder()
                    .assetId(asset.assetId())
                    .url(asset.url())
                    .objectKey(asset.objectKey())
                    .originalName(asset.originalName())
                    .size(asset.size())
                    .fileType(asset.fileType())
                    .build())
                .toList())
            .build();
    }

    private ProctoringPolicyView policy(String level, String configJson) {
        try {
            return configJson == null
                ? ProctoringPolicyView.builder().level(level).build()
                : mapper.readValue(configJson, ProctoringPolicyView.class);
        } catch (Exception exception) {
            return ProctoringPolicyView.builder().level(level).build();
        }
    }

    private LocalDateTime dbNow() {
        return jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
    }

    private Long requireUser() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("未登录");
        }
        return userId;
    }

    private record SessionRow(Long id, LocalDateTime start, LocalDateTime deadline, String status) {
    }
}
