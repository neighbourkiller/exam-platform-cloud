package com.ekusys.exam.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ekusys.exam.academic.api.ClassRosterView;
import com.ekusys.exam.academic.api.SubjectSummary;
import com.ekusys.exam.academic.api.TeachingClassBatchRequest;
import com.ekusys.exam.academic.api.TeachingClassSummary;
import com.ekusys.exam.common.enums.ExamStatus;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.content.api.PaperSnapshotView;
import com.ekusys.exam.content.api.PaperSummary;
import com.ekusys.exam.exam.dto.ExamCreateRequest;
import com.ekusys.exam.exam.dto.TeacherExamView;
import com.ekusys.exam.management.api.RuntimeExamAdmission;
import com.ekusys.exam.management.api.RuntimeExamMetadata;
import com.ekusys.exam.management.api.RuntimeExamProctoringContext;
import com.ekusys.exam.management.api.RuntimeExamSnapshot;
import com.ekusys.exam.management.api.RuntimeStudentExamSummary;
import com.ekusys.exam.management.client.AcademicRosterClient;
import com.ekusys.exam.management.client.ContentSnapshotClient;
import com.ekusys.exam.management.messaging.ManagementOutboxService;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.ExamTargetClass;
import com.ekusys.exam.repository.mapper.ExamMapper;
import com.ekusys.exam.repository.mapper.ExamTargetClassMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ExamManagementService {
    private final ExamMapper examMapper;
    private final ExamTargetClassMapper targetMapper;
    private final JdbcTemplate jdbc;
    private final AcademicRosterClient academic;
    private final ContentSnapshotClient content;
    private final ObjectMapper mapper;
    private final ManagementOutboxService outbox;
    private final ExamMetadataCacheService metadataCache;
    private final ExamCacheWarmService cacheWarmService;

    public ExamManagementService(ExamMapper examMapper, ExamTargetClassMapper targetMapper, JdbcTemplate jdbc,
                                 AcademicRosterClient academic, ContentSnapshotClient content, ObjectMapper mapper,
                                 ManagementOutboxService outbox, ExamMetadataCacheService metadataCache,
                                 ExamCacheWarmService cacheWarmService) {
        this.examMapper = examMapper;
        this.targetMapper = targetMapper;
        this.jdbc = jdbc;
        this.academic = academic;
        this.content = content;
        this.mapper = mapper;
        this.outbox = outbox;
        this.metadataCache = metadataCache;
        this.cacheWarmService = cacheWarmService;
    }

    @Transactional
    public Long create(ExamCreateRequest request) {
        validateCreate(request);
        Exam exam = new Exam();
        exam.setName(request.getName());
        exam.setPaperId(request.getPaperId());
        exam.setStartTime(request.getStartTime());
        exam.setEndTime(request.getEndTime());
        exam.setDurationMinutes(request.getDurationMinutes());
        exam.setPassScore(request.getPassScore());
        exam.setStatus(ExamStatus.DRAFT.name());
        exam.setPublisherId(SecurityUtils.getCurrentUserId());
        exam.setProctoringLevel(request.getProctoringLevel() == null ? "STANDARD" : request.getProctoringLevel());
        try {
            exam.setProctoringConfigJson(
                request.getProctoringPolicy() == null ? null : mapper.writeValueAsString(request.getProctoringPolicy())
            );
        } catch (Exception exception) {
            throw new BusinessException("监考策略格式错误");
        }
        examMapper.insert(exam);
        for (Long classId : request.getTargetClassIds().stream().distinct().toList()) {
            ExamTargetClass link = new ExamTargetClass();
            link.setExamId(exam.getId());
            link.setClassId(classId);
            targetMapper.insert(link);
        }
        return exam.getId();
    }

    @Transactional
    public Long createAndOptionallyPublish(ExamCreateRequest request, boolean autoPublish) {
        Long id = create(request);
        if (autoPublish) {
            publish(id);
        }
        return id;
    }

    public void validateCreate(ExamCreateRequest request) {
        if (request.getStartTime() == null || request.getEndTime() == null
            || !request.getStartTime().isBefore(request.getEndTime())) {
            throw new BusinessException("考试开始时间必须早于结束时间");
        }
        if (request.getDurationMinutes() == null || request.getDurationMinutes() < 1) {
            throw new BusinessException("考试时长必须大于0");
        }
        if (request.getPassScore() == null || request.getPassScore() < 0) {
            throw new BusinessException("及格分不能小于0");
        }
        List<Long> classIds = request.getTargetClassIds() == null
            ? List.of()
            : request.getTargetClassIds().stream().filter(Objects::nonNull).distinct().toList();
        if (classIds.isEmpty()) {
            throw new BusinessException("目标教学班不能为空");
        }
        PaperSummary paper = content.summary(request.getPaperId()).getData();
        if (paper == null) {
            throw new BusinessException("试卷不存在");
        }
        if (paper.subjectId() == null) {
            throw new BusinessException("试卷未绑定课程，无法发布考试");
        }
        List<TeachingClassSummary> classes = academic.classSummaries(
            new TeachingClassBatchRequest(classIds)
        ).getData();
        if (classes == null || classes.size() != classIds.size()) {
            throw new BusinessException("存在无效教学班ID");
        }
        for (TeachingClassSummary value : classes) {
            if (!Objects.equals(paper.subjectId(), value.subjectId())) {
                throw new BusinessException("目标教学班课程与试卷课程不一致");
            }
        }
    }

    @Transactional
    public void publish(Long id) {
        Exam exam = requireManage(id);
        if (!ExamStatus.DRAFT.name().equals(exam.getStatus())) {
            throw new BusinessException("只有草稿考试可以发布");
        }
        PaperSnapshotView snapshot = content.create(exam.getPaperId()).getData();
        jdbc.update("delete from exam_candidate where exam_id=?", id);
        Map<Long, String> classNames = new LinkedHashMap<>();
        List<ExamTargetClass> targets = targetMapper.selectList(
            new LambdaQueryWrapper<ExamTargetClass>().eq(ExamTargetClass::getExamId, id)
        );
        for (ExamTargetClass target : targets) {
            ClassRosterView roster = academic.roster(target.getClassId()).getData();
            classNames.put(target.getClassId(), roster.className());
            for (Long studentId : roster.studentIds()) {
                jdbc.update(
                    """
                        insert ignore into exam_candidate(
                            id,exam_id,student_id,class_id,roster_version,created_at
                        ) values(?,?,?,?,?,current_timestamp(3))
                        """,
                    IdWorker.getId(), id, studentId, target.getClassId(), roster.version()
                );
            }
        }
        jdbc.update(
            """
                insert into exam_paper_ref(
                    id,exam_id,paper_snapshot_id,snapshot_version,created_at
                ) values(?,?,?,?,current_timestamp(3))
                on duplicate key update paper_snapshot_id=values(paper_snapshot_id),
                                        snapshot_version=values(snapshot_version)
                """,
            IdWorker.getId(), id, snapshot.snapshotId(), snapshot.version()
        );
        exam.setStatus(ExamStatus.PUBLISHED.name());
        examMapper.updateById(exam);
        SubjectSummary subject = academic.subject(snapshot.subjectId()).getData();
        outbox.examPublished(exam, snapshot, subject, classNames);

        RuntimeExamMetadata metadata = metadata(exam, snapshot.snapshotId(), snapshot.version());
        metadataCache.putAfterCommit(metadata);
        afterCommit(() -> cacheWarmService.warmPaper(id, snapshot.snapshotId()));
    }

    @Transactional
    public void terminate(Long id) {
        Exam exam = requireManage(id);
        exam.setStatus(ExamStatus.TERMINATED.name());
        examMapper.updateById(exam);
        outbox.examTerminated(exam);
        metadataCache.evictAfterCommit(id);
    }

    public List<TeacherExamView> listTeacher() {
        LambdaQueryWrapper<Exam> query = new LambdaQueryWrapper<Exam>().orderByDesc(Exam::getCreateTime);
        if (!SecurityUtils.getCurrentRoles().contains("ADMIN")) {
            query.eq(Exam::getPublisherId, SecurityUtils.getCurrentUserId());
        }
        return examMapper.selectList(query).stream().map(this::view).toList();
    }

    public boolean isPaperUsed(Long paperId) {
        return examMapper.selectCount(new LambdaQueryWrapper<Exam>().eq(Exam::getPaperId, paperId)) > 0;
    }

    public com.ekusys.exam.management.api.ExamRegradeContext regradeContext(Long id) {
        return jdbc.queryForObject("""
            select e.id,e.publisher_id,e.status,e.end_time,r.paper_snapshot_id
            from exam e join exam_paper_ref r on r.exam_id=e.id where e.id=?
            """, (rs, n) -> new com.ekusys.exam.management.api.ExamRegradeContext(rs.getLong("id"),
                rs.getObject("publisher_id", Long.class), rs.getLong("paper_snapshot_id"), rs.getString("status"),
                rs.getObject("end_time", java.time.LocalDateTime.class)), id);
    }

    public RuntimeExamMetadata runtimeMetadata(Long id) {
        return metadataCache.get(id, () -> loadMetadata(id));
    }

    public RuntimeExamAdmission admission(Long id, Long studentId) {
        AdmissionCheck check = admissionCheck(id, studentId);
        if (!check.eligible()) {
            throw new BusinessException("你不在本场考试名单中");
        }
        if (!ExamStatus.PUBLISHED.name().equals(check.status())) {
            throw new BusinessException("考试未发布或已终止");
        }
        RuntimeExamMetadata metadata = runtimeMetadata(id);
        return new RuntimeExamAdmission(metadata, check.status(), content.delivery(metadata.paperSnapshotId()).getData());
    }

    public List<RuntimeStudentExamSummary> studentSummaries(Long studentId) {
        return jdbc.query(
            """
                select e.id,e.name,e.start_time,e.end_time,e.duration_minutes,e.status,
                       e.proctoring_level,e.proctoring_config_json
                  from exam_candidate c
                  join exam e on e.id=c.exam_id
                 where c.student_id=?
                 order by e.id desc
                """,
            (rs, rowNum) -> new RuntimeStudentExamSummary(
                rs.getLong("id"), rs.getString("name"),
                rs.getObject("start_time", java.time.LocalDateTime.class),
                rs.getObject("end_time", java.time.LocalDateTime.class),
                rs.getInt("duration_minutes"), rs.getString("status"),
                rs.getString("proctoring_level"), rs.getString("proctoring_config_json")
            ),
            studentId
        );
    }

    public RuntimeExamProctoringContext proctoringContext(Long id) {
        RuntimeExamMetadata metadata = runtimeMetadata(id);
        String status = jdbc.queryForObject("select status from exam where id=?", String.class, id);
        List<Long> candidates = jdbc.queryForList(
            "select student_id from exam_candidate where exam_id=? order by student_id",
            Long.class,
            id
        );
        return new RuntimeExamProctoringContext(metadata, status, candidates);
    }

    /**
     * Compatibility adapter for older Runtime deployments. New code uses the split endpoints.
     */
    @Deprecated
    public RuntimeExamSnapshot runtimeSnapshot(Long id) {
        RuntimeExamProctoringContext context = proctoringContext(id);
        RuntimeExamMetadata metadata = context.metadata();
        return new RuntimeExamSnapshot(
            id, metadata.name(), metadata.startTime(), metadata.endTime(), metadata.durationMinutes(),
            metadata.passScore(), context.status(), metadata.publisherId(), metadata.proctoringLevel(),
            metadata.proctoringConfigJson(), context.candidateIds(),
            content.delivery(metadata.paperSnapshotId()).getData()
        );
    }

    /**
     * Compatibility adapter for older Runtime deployments. New code uses studentSummaries.
     */
    @Deprecated
    public List<RuntimeExamSnapshot> studentSnapshots(Long studentId) {
        return jdbc.queryForList(
            "select exam_id from exam_candidate where student_id=? order by exam_id desc",
            Long.class,
            studentId
        ).stream().map(this::runtimeSnapshot).toList();
    }

    private RuntimeExamMetadata loadMetadata(Long id) {
        List<RuntimeExamMetadata> values = jdbc.query(
            """
                select e.id,e.name,e.start_time,e.end_time,e.duration_minutes,e.pass_score,
                       e.publisher_id,e.proctoring_level,e.proctoring_config_json,
                       r.paper_snapshot_id,r.snapshot_version
                  from exam e
                  join exam_paper_ref r on r.exam_id=e.id
                 where e.id=?
                """,
            (rs, rowNum) -> new RuntimeExamMetadata(
                rs.getLong("id"), rs.getString("name"),
                rs.getObject("start_time", java.time.LocalDateTime.class),
                rs.getObject("end_time", java.time.LocalDateTime.class),
                rs.getInt("duration_minutes"), rs.getInt("pass_score"),
                rs.getLong("paper_snapshot_id"), rs.getLong("snapshot_version"),
                nullableLong(rs, "publisher_id"), rs.getString("proctoring_level"),
                rs.getString("proctoring_config_json")
            ),
            id
        );
        if (values.isEmpty()) {
            throw new BusinessException("考试不存在或尚未发布");
        }
        return values.getFirst();
    }

    private AdmissionCheck admissionCheck(Long id, Long studentId) {
        List<AdmissionCheck> values = jdbc.query(
            """
                select e.status,
                       exists(
                           select 1 from exam_candidate c
                            where c.exam_id=e.id and c.student_id=?
                       ) eligible
                  from exam e
                 where e.id=?
                """,
            (rs, rowNum) -> new AdmissionCheck(rs.getString("status"), rs.getBoolean("eligible")),
            studentId,
            id
        );
        if (values.isEmpty()) {
            throw new BusinessException("考试不存在");
        }
        return values.getFirst();
    }

    private Exam requireManage(Long id) {
        Exam exam = examMapper.selectById(id);
        if (exam == null) {
            throw new BusinessException("考试不存在");
        }
        if (!SecurityUtils.getCurrentRoles().contains("ADMIN")
            && !Objects.equals(exam.getPublisherId(), SecurityUtils.getCurrentUserId())) {
            throw new BusinessException("无权限管理该考试");
        }
        return exam;
    }

    private TeacherExamView view(Exam exam) {
        return TeacherExamView.builder()
            .examId(exam.getId())
            .name(exam.getName())
            .startTime(exam.getStartTime())
            .endTime(exam.getEndTime())
            .durationMinutes(exam.getDurationMinutes())
            .passScore(exam.getPassScore())
            .status(exam.getStatus())
            .proctoringLevel(exam.getProctoringLevel())
            .build();
    }

    private RuntimeExamMetadata metadata(Exam exam, Long snapshotId, long snapshotVersion) {
        return new RuntimeExamMetadata(
            exam.getId(), exam.getName(), exam.getStartTime(), exam.getEndTime(),
            exam.getDurationMinutes(), exam.getPassScore(), snapshotId, snapshotVersion,
            exam.getPublisherId(), exam.getProctoringLevel(), exam.getProctoringConfigJson()
        );
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column) == null ? null : rs.getLong(column);
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private record AdmissionCheck(String status, boolean eligible) {
    }
}
