package com.ekusys.exam.runtime.entry;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.content.api.PaperSnapshotQuestion;
import com.ekusys.exam.content.api.PaperSnapshotView;
import com.ekusys.exam.exam.dto.EntryActivateRequest;
import com.ekusys.exam.exam.dto.EntryActivateView;
import com.ekusys.exam.exam.dto.EntryPrepareRequest;
import com.ekusys.exam.exam.dto.EntryPrepareView;
import com.ekusys.exam.exam.dto.ExamClientLeaseView;
import com.ekusys.exam.exam.dto.PaperDeliveryRequest;
import com.ekusys.exam.exam.dto.PaperDeliveryView;
import com.ekusys.exam.exam.dto.PaperDraftAnswerView;
import com.ekusys.exam.exam.dto.PaperQuestionView;
import com.ekusys.exam.exam.dto.ProctoringPolicyView;
import com.ekusys.exam.exam.dto.StartExamRequest;
import com.ekusys.exam.exam.dto.StartExamResponse;
import com.ekusys.exam.exam.dto.StudentExamQuestionView;
import com.ekusys.exam.runtime.config.ExamEntryProperties;
import com.ekusys.exam.runtime.entry.ExamActivationTransactionService.ActivationResult;
import com.ekusys.exam.runtime.service.ExamClientLeaseService;
import com.ekusys.exam.runtime.service.ExamSnapshotService;
import com.ekusys.exam.runtime.service.SnapshotDraft;
import com.ekusys.exam.runtime.service.RuntimeTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ExamEntryService {
    private final JdbcTemplate jdbc;
    private final RuntimeExamDefinitionRepository definitions;
    private final ExamEntryTicketService tickets;
    private final PaperDeliveryCache paperCache;
    private final ExamClientLeaseService leases;
    private final ExamSnapshotService snapshots;
    private final ExamActivationTransactionService activation;
    private final ObjectMapper mapper;
    private final ExamEntryProperties properties;
    private final ExamEntryMetrics metrics;

    public ExamEntryService(
        JdbcTemplate jdbc,
        RuntimeExamDefinitionRepository definitions,
        ExamEntryTicketService tickets,
        PaperDeliveryCache paperCache,
        ExamClientLeaseService leases,
        ExamSnapshotService snapshots,
        ExamActivationTransactionService activation,
        ObjectMapper mapper,
        ExamEntryProperties properties,
        ExamEntryMetrics metrics
    ) {
        this.jdbc = jdbc;
        this.definitions = definitions;
        this.tickets = tickets;
        this.paperCache = paperCache;
        this.leases = leases;
        this.snapshots = snapshots;
        this.activation = activation;
        this.mapper = mapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public boolean hasLocalProjection(Long examId) {
        return definitions.find(examId) != null;
    }

    public EntryPrepareView prepare(Long examId, EntryPrepareRequest request) {
        ensureEnabled();
        return measured("prepare", () -> prepareInternal(examId, request));
    }

    public EntryActivateView activate(Long examId, EntryActivateRequest request) {
        ensureEnabled();
        return measured("activate", () -> activateInternal(examId, request, true));
    }

    public PaperDeliveryView paperDelivery(Long examId, PaperDeliveryRequest request) {
        ensureEnabled();
        return measured("paper-delivery", () -> paperDeliveryInternal(examId, request));
    }

    public StartExamResponse legacyStart(Long examId, StartExamRequest request) {
        String clientId = request == null ? null : request.getClientId();
        String leaseToken = request == null ? null : request.getLeaseToken();
        RuntimeExamDefinition compatibilityDefinition = requireReadyDefinition(examId);
        if (compatibilityDefinition.terminated()) {
            throw new BusinessException("本场考试已终止");
        }
        LocalDateTime compatibilityNow = dbNow();
        if (compatibilityNow.isBefore(compatibilityDefinition.startTime())) {
            throw new BusinessException("当前不在考试时间内");
        }
        EntryPrepareView prepared = measured(
            "prepare", () -> prepareInternal(examId, new EntryPrepareRequest(clientId))
        );
        if ("SUBMITTED".equals(prepared.status()) || "TERMINATED".equals(prepared.status())) {
            throw new BusinessException("你已提交过本场考试或考试已终止");
        }
        EntryActivateView activated = measured(
            "activate",
            () -> activateInternal(
                examId, new EntryActivateRequest(clientId, prepared.entryToken(), leaseToken), false
            )
        );
        if (!"STARTED".equals(activated.status()) && !"RESUMED".equals(activated.status())) {
            throw new BusinessException("考试会话正在提交或已结束");
        }
        PaperDeliveryView delivered = measured(
            "paper-delivery",
            () -> paperDeliveryInternal(
                examId, new PaperDeliveryRequest(clientId, activated.leaseToken())
            )
        );
        RuntimeExamDefinition definition = requireReadyDefinition(examId);
        Map<Long, String> draft = delivered.draftAnswers().stream()
            .collect(java.util.stream.Collectors.toMap(
                PaperDraftAnswerView::questionId, PaperDraftAnswerView::answerText,
                (left, right) -> right
            ));
        return StartExamResponse.builder()
            .examId(examId)
            .examName(delivered.examName())
            .resumed(activated.resumed())
            .durationMinutes(definition.durationMinutes())
            .startTime(activated.startTime())
            .endTime(activated.endTime())
            .deadlineTime(activated.deadlineTime())
            .serverEpochMs(activated.serverEpochMs())
            .deadlineEpochMs(activated.deadlineEpochMs())
            .draftUpdatedAt(delivered.draftUpdatedAt())
            .leaseToken(activated.leaseToken())
            .leaseExpiresAt(activated.leaseExpiresAt())
            .heartbeatIntervalSeconds(activated.heartbeatIntervalSeconds())
            .leaseTimeoutSeconds(activated.leaseTimeoutSeconds())
            .proctoringPolicy(delivered.proctoringPolicy())
            .questions(delivered.questions().stream()
                .map(question -> StudentExamQuestionView.builder()
                    .questionId(question.questionId())
                    .type(question.type())
                    .content(question.content())
                    .optionsJson(question.optionsJson())
                    .score(question.score())
                    .sortOrder(question.sortOrder())
                    .assets(question.assets())
                    .currentAnswer(draft.get(question.questionId()))
                    .build())
                .toList())
            .build();
    }

    private EntryPrepareView prepareInternal(Long examId, EntryPrepareRequest request) {
        Long studentId = requireUser();
        LocalDateTime now = dbNow();
        RuntimeExamDefinition definition = requireReadyDefinition(examId);
        if (definition.terminated()) {
            return new EntryPrepareView(
                examId, null, now, RuntimeTime.epochMillis(now), null, -1, "TERMINATED"
            );
        }
        RuntimeEntrySession session = definitions.findSession(examId, studentId);
        if (session == null) {
            throw new ExamEntryException(
                HttpStatus.FORBIDDEN, "EXAM_NOT_CANDIDATE", "你不在本场考试名单中"
            );
        }
        if (definition.terminated() || "CANCELLED".equals(session.status())) {
            return new EntryPrepareView(
                examId, null, now, RuntimeTime.epochMillis(now), null, -1, "TERMINATED"
            );
        }
        if (terminal(session.status())) {
            return new EntryPrepareView(
                examId, null, now, RuntimeTime.epochMillis(now), null, -1, "SUBMITTED"
            );
        }
        LocalDateTime waitingOpens = definition.startTime()
            .minusMinutes(properties.safeWaitingRoomMinutes());
        if (now.isBefore(waitingOpens)) {
            long retry = Math.max(1L, Duration.between(now, waitingOpens).toMillis());
            throw new ExamEntryException(
                HttpStatus.TOO_EARLY, "EXAM_ENTRY_NOT_OPEN", "候场尚未开放", retry
            );
        }
        if (!now.isBefore(definition.endTime())) {
            throw new ExamEntryException(HttpStatus.GONE, "EXAM_ENTRY_CLOSED", "本场考试已结束");
        }

        int slot = ExamEntryTicketService.stableSlot(
            examId, studentId, properties.safeSlotCount()
        );
        LocalDateTime scheduledAt = definition.startTime().plusSeconds(slot);
        ExamEntryTicketService.EntryTicket ticket = tickets.issue(
            examId, studentId, request.clientId(), slot, scheduledAt,
            definition.endTime(), now
        );
        if (ticket.newlyIssued()) {
            metrics.slot(slot);
        }
        return new EntryPrepareView(
            examId, ticket.token(), now, RuntimeTime.epochMillis(now), scheduledAt, slot,
            now.isBefore(scheduledAt) ? "WAITING" : "READY"
        );
    }

    private EntryActivateView activateInternal(Long examId, EntryActivateRequest request,
                                               boolean enforceSlot) {
        Long studentId = requireUser();
        RuntimeExamDefinition definition = requireReadyDefinition(examId);
        RuntimeEntrySession session = definitions.findSession(examId, studentId);
        if (session == null) {
            throw new ExamEntryException(
                HttpStatus.FORBIDDEN, "EXAM_NOT_CANDIDATE", "你不在本场考试名单中"
            );
        }
        LocalDateTime now = dbNow();
        ExamEntryTicketService.EntryTicket ticket = tickets.require(
            request.entryToken(), examId, studentId, request.clientId()
        );
        if (enforceSlot && now.isBefore(ticket.scheduledAt())) {
            long retry = Math.max(1L, Duration.between(now, ticket.scheduledAt()).toMillis());
            throw new ExamEntryException(
                HttpStatus.TOO_EARLY, "EXAM_ENTRY_NOT_READY", "尚未到分配的激活时刻", retry
            );
        }
        if (!now.isBefore(definition.endTime())) {
            throw new ExamEntryException(HttpStatus.GONE, "EXAM_ENTRY_CLOSED", "本场考试已结束");
        }
        if (terminal(session.status())) {
            return terminalActivation(definition, session, now);
        }
        if (definition.terminated() || "CANCELLED".equals(session.status())) {
            throw new ExamEntryException(HttpStatus.GONE, "EXAM_TERMINATED", "本场考试已终止");
        }

        // The paper must be ready before the database transition starts the personal timer.
        requireCachedPaper(definition);

        ActivationResult result = activation.activate(definition, studentId, request.clientId());

        if ("CANCELLED".equals(result.session().status())) {
            throw new ExamEntryException(HttpStatus.GONE, "EXAM_TERMINATED", "本场考试已终止");
        }
        if (!"ANSWERING".equals(result.session().status())) {
            return terminalActivation(definition, result.session(), result.dbNow());
        }
        ExamClientLeaseView lease = activateLease(
            examId, studentId, request, result.session(), result.dbNow()
        );
        return new EntryActivateView(
            examId, result.newlyStarted() ? "STARTED" : "RESUMED", !result.newlyStarted(),
            result.dbNow(), RuntimeTime.epochMillis(result.dbNow()),
            result.session().startTime(), definition.endTime(), result.session().deadlineTime(),
            RuntimeTime.epochMillis(result.session().deadlineTime()),
            lease.getLeaseToken(), lease.getLeaseExpiresAt(),
            lease.getHeartbeatIntervalSeconds(), lease.getLeaseTimeoutSeconds(),
            definition.paperSnapshotVersion()
        );
    }

    private ExamClientLeaseView activateLease(Long examId, Long studentId,
                                              EntryActivateRequest request,
                                              RuntimeEntrySession session,
                                              LocalDateTime now) {
        try {
            String requestedToken = hasText(request.leaseToken()) ? request.leaseToken() : null;
            boolean sameClient = request.clientId().equals(session.activeClientId());
            if (sameClient && session.activeClientToken() != null
                && (requestedToken == null || requestedToken.equals(session.activeClientToken()))) {
                return leases.activateCommitted(
                    examId, studentId, session.id(), session.deadlineTime(), request.clientId(),
                    session.activeClientToken(), now
                );
            }
            return leases.acquire(
                examId, studentId, session.id(), session.deadlineTime(), request.clientId(),
                requestedToken, now
            );
        } catch (BusinessException exception) {
            throw leaseException(exception);
        }
    }

    private PaperDeliveryView paperDeliveryInternal(Long examId, PaperDeliveryRequest request) {
        Long studentId = requireUser();
        RuntimeExamDefinition definition = requireReadyDefinition(examId);
        RuntimeEntrySession session = definitions.findSession(examId, studentId);
        if (session == null) {
            throw new ExamEntryException(
                HttpStatus.FORBIDDEN, "EXAM_NOT_CANDIDATE", "你不在本场考试名单中"
            );
        }
        if (!"ANSWERING".equals(session.status())) {
            throw new ExamEntryException(HttpStatus.CONFLICT, "EXAM_SESSION_NOT_ACTIVE", "考试会话未处于答题状态");
        }
        LocalDateTime now = dbNow();
        try {
            leases.requireCurrent(session.id(), request.clientId(), request.leaseToken(), now);
        } catch (BusinessException exception) {
            throw leaseException(exception);
        }
        PaperSnapshotView paper = requireCachedPaper(definition);
        int firstDelivery = jdbc.update(
            """
                update exam_session
                   set first_paper_delivered_at=?,update_time=current_timestamp(3)
                 where id=? and status='ANSWERING' and first_paper_delivered_at is null
                """,
            now, session.id()
        );
        SnapshotDraft draft = firstDelivery == 1
            ? SnapshotDraft.empty()
            : snapshots.loadLatestDraft(examId, studentId);
        return new PaperDeliveryView(
            examId, definition.name(), definition.paperSnapshotVersion(),
            policy(definition.proctoringLevel(), definition.proctoringConfigJson()),
            paper.questions().stream().map(this::paperQuestion).toList(),
            draft.answers().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new PaperDraftAnswerView(entry.getKey(), entry.getValue()))
                .toList(),
            draft.version(), draft.updatedAt()
        );
    }

    private RuntimeExamDefinition requireReadyDefinition(Long examId) {
        RuntimeExamDefinition definition = definitions.find(examId);
        if (definition == null || (!definition.ready() && !definition.terminated())) {
            throw new ExamEntryException(
                HttpStatus.SERVICE_UNAVAILABLE, "EXAM_PREPARING",
                "考试数据正在准备，请稍后重试", 500L
            );
        }
        return definition;
    }

    private PaperSnapshotView requireCachedPaper(RuntimeExamDefinition definition) {
        PaperSnapshotView paper = paperCache.get(definition.paperSnapshotId());
        if (!definition.paperSnapshotId().equals(paper.snapshotId())
            || definition.paperSnapshotVersion() != paper.version()) {
            throw new ExamEntryException(
                HttpStatus.SERVICE_UNAVAILABLE, "EXAM_PAPER_UNAVAILABLE",
                "考试试卷快照版本尚未准备完成", 500L
            );
        }
        return paper;
    }

    private EntryActivateView terminalActivation(RuntimeExamDefinition definition,
                                                 RuntimeEntrySession session,
                                                 LocalDateTime now) {
        String status = "AUTO_SUBMITTING".equals(session.status()) ? "SUBMITTING" : "SUBMITTED";
        return new EntryActivateView(
            definition.examId(), status, true, now, RuntimeTime.epochMillis(now),
            session.startTime(), definition.endTime(), session.deadlineTime(),
            RuntimeTime.nullableEpochMillis(session.deadlineTime()),
            null, null, null, null, definition.paperSnapshotVersion()
        );
    }

    private PaperQuestionView paperQuestion(PaperSnapshotQuestion question) {
        return new PaperQuestionView(
            question.questionId(), question.type(), question.content(), question.optionsJson(),
            question.score(), question.sortOrder(), question.assets().stream()
                .map(asset -> com.ekusys.exam.question.dto.QuestionImageUploadView.builder()
                    .assetId(asset.assetId())
                    .url(asset.url())
                    .objectKey(asset.objectKey())
                    .originalName(asset.originalName())
                    .size(asset.size())
                    .fileType(asset.fileType())
                    .build())
                .toList()
        );
    }

    private ProctoringPolicyView policy(String level, String configJson) {
        try {
            return configJson == null || configJson.isBlank()
                ? ProctoringPolicyView.builder().level(level).build()
                : mapper.readValue(configJson, ProctoringPolicyView.class);
        } catch (Exception exception) {
            return ProctoringPolicyView.builder().level(level).build();
        }
    }

    private ExamEntryException leaseException(BusinessException exception) {
        if (ExamClientLeaseService.CONFLICT_CODE.equals(exception.getCode())) {
            return new ExamEntryException(HttpStatus.CONFLICT, exception.getCode(), exception.getMessage());
        }
        if (ExamClientLeaseService.UNAVAILABLE_CODE.equals(exception.getCode())) {
            return new ExamEntryException(
                HttpStatus.SERVICE_UNAVAILABLE, exception.getCode(), exception.getMessage(), 500L
            );
        }
        return new ExamEntryException(
            HttpStatus.BAD_REQUEST,
            exception.getCode() == null ? "EXAM_ENTRY_REJECTED" : exception.getCode(),
            exception.getMessage()
        );
    }

    private <T> T measured(String endpoint, Supplier<T> action) {
        Timer.Sample sample = metrics.start();
        try {
            T result = action.get();
            metrics.finish(sample, endpoint, "SUCCESS", null);
            return result;
        } catch (ExamEntryException exception) {
            metrics.finish(sample, endpoint, "ERROR", exception.code());
            throw exception;
        } catch (RuntimeException exception) {
            metrics.finish(sample, endpoint, "ERROR", "INTERNAL");
            throw exception;
        }
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new ExamEntryException(
                HttpStatus.SERVICE_UNAVAILABLE, "EXAM_ENTRY_V2_DISABLED",
                "新版考试入场尚未启用", 1_000L
            );
        }
    }

    private Long requireUser() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new ExamEntryException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未登录");
        }
        return userId;
    }

    private LocalDateTime dbNow() {
        return jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
    }

    private boolean terminal(String status) {
        return "SUBMITTED".equals(status) || "AUTO_SUBMITTING".equals(status);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
