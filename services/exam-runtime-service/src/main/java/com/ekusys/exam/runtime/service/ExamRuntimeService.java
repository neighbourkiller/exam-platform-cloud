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
import com.ekusys.exam.management.api.RuntimeExamSnapshot;
import com.ekusys.exam.runtime.api.GradingAnswerInput;
import com.ekusys.exam.runtime.api.GradingSubmissionInput;
import com.ekusys.exam.runtime.client.ManagementRuntimeClient;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public ExamRuntimeService(JdbcTemplate jdbc, ManagementRuntimeClient management,
                              ObjectMapper mapper, RuntimeOutboxService outbox,
                              TimeoutSubmissionService timeoutSubmissionService,
                              ExamSnapshotService snapshotService,
                              ExamClientLeaseService clientLeaseService,
                              ExamAnswerInputValidator answerInputValidator) {
        this.jdbc = jdbc;
        this.management = management;
        this.mapper = mapper;
        this.outbox = outbox;
        this.timeoutSubmissionService = timeoutSubmissionService;
        this.snapshotService = snapshotService;
        this.clientLeaseService = clientLeaseService;
        this.answerInputValidator = answerInputValidator;
    }

    public List<StudentExamView> listStudent() {
        Long userId = requireUser();
        return management.student(userId).getData().stream()
            .map(snapshot -> StudentExamView.builder()
                .examId(snapshot.examId())
                .name(snapshot.name())
                .startTime(snapshot.startTime())
                .endTime(snapshot.endTime())
                .durationMinutes(snapshot.durationMinutes())
                .status(snapshot.status())
                .submitted(submitted(snapshot.examId(), userId))
                .proctoringLevel(snapshot.proctoringLevel())
                .proctoringPolicy(policy(snapshot))
                .build())
            .toList();
    }

    @Transactional
    public StartExamResponse start(Long examId, StartExamRequest request) {
        Long userId = requireUser();
        RuntimeExamSnapshot exam = management.snapshot(examId).getData();
        LocalDateTime now = dbNow();
        if (!exam.candidateIds().contains(userId)) {
            throw new BusinessException("你不在本场考试名单中");
        }
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
            lease = clientLeaseService.acquire(session.id(), clientId(request), leaseToken(request), now);
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
                    IdWorker.getId(), examId, userId, exam.paper().snapshotId()
                );
                outbox.sessionStarted(examId, userId);
                session = new SessionRow(sessionId, now, deadline, "ANSWERING");
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
                lease = clientLeaseService.acquire(session.id(), clientId(request), leaseToken(request), now);
            }
        }

        SnapshotDraft draft = snapshotService.loadLatestDraft(examId, userId);
        List<StudentExamQuestionView> questions = exam.paper().questions().stream()
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
            .proctoringPolicy(policy(exam))
            .questions(questions)
            .build();
    }

    public ExamClientLeaseView heartbeat(Long examId, ExamClientLeaseRequest request) {
        Long userId = requireUser();
        SessionRow session = active(examId, userId);
        LocalDateTime now = dbNow();
        if (!now.isBefore(session.deadline())) {
            throw new BusinessException("考试作答时间已结束");
        }
        return clientLeaseService.renew(session.id(), clientId(request), leaseToken(request), now);
    }

    public SnapshotAckView snapshot(Long examId, SnapshotRequest request) {
        Long userId = requireUser();
        SessionRow session = active(examId, userId);
        LocalDateTime now = dbNow();
        if (!now.isBefore(session.deadline())) {
            throw new BusinessException("考试作答时间已结束");
        }
        answerInputValidator.validateAnswers(request.getAnswers());
        answerInputValidator.validateSnapshotVersion(request, now);
        ExamClientLeaseView lease = clientLeaseService.renew(session.id(), request.getClientId(), request.getLeaseToken(), now);
        SnapshotAckView ack = snapshotService.save(
            examId, userId, session.id(), session.deadline(), now, request
        );
        ack.setLeaseToken(lease.getLeaseToken());
        ack.setLeaseExpiresAt(lease.getLeaseExpiresAt());
        ack.setHeartbeatIntervalSeconds(lease.getHeartbeatIntervalSeconds());
        ack.setLeaseTimeoutSeconds(lease.getLeaseTimeoutSeconds());
        return ack;
    }

    @Transactional
    public SubmitResultView submit(Long examId, SubmitExamRequest request) {
        Long userId = requireUser();
        SessionRow session = active(examId, userId);
        LocalDateTime now = dbNow();
        boolean expired = !now.isBefore(session.deadline());
        Long submissionId = submissionId(examId, userId);
        if (expired) {
            timeoutSubmissionService.submitExpired(session.id(), examId, userId);
            return SubmitResultView.builder().submissionId(submissionId).status("PROCESSING").build();
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
        return SubmitResultView.builder().submissionId(submissionId).status("PROCESSING").build();
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
        RuntimeExamSnapshot exam = management.snapshot(row.examId()).getData();
        List<GradingAnswerInput> answers = jdbc.query(
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
        List<SessionRow> rows = findSessions(examId, userId);
        if (rows.isEmpty() || !"ANSWERING".equals(rows.getFirst().status())) {
            throw new BusinessException("会话已结束");
        }
        return rows.getFirst();
    }

    private Long submissionId(Long examId, Long userId) {
        return jdbc.queryForObject(
            "select id from submission where exam_id=? and student_id=?",
            Long.class, examId, userId
        );
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

    private boolean submitted(Long examId, Long userId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from submission where exam_id=? and student_id=? and status<>'IN_PROGRESS'",
            Integer.class, examId, userId
        );
        return count != null && count > 0;
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

    private ProctoringPolicyView policy(RuntimeExamSnapshot exam) {
        try {
            return exam.proctoringConfigJson() == null
                ? ProctoringPolicyView.builder().level(exam.proctoringLevel()).build()
                : mapper.readValue(exam.proctoringConfigJson(), ProctoringPolicyView.class);
        } catch (Exception exception) {
            return ProctoringPolicyView.builder().level(exam.proctoringLevel()).build();
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
