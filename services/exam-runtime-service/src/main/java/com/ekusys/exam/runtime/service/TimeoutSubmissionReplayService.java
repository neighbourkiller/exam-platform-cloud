package com.ekusys.exam.runtime.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.runtime.entry.RuntimeExamDefinition;
import com.ekusys.exam.runtime.entry.RuntimeExamDefinitionRepository;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TimeoutSubmissionReplayService {
    private final JdbcTemplate jdbc;
    private final RuntimeExamDefinitionRepository definitions;
    private final RuntimeOutboxService outbox;

    public TimeoutSubmissionReplayService(JdbcTemplate jdbc,
                                          RuntimeExamDefinitionRepository definitions,
                                          RuntimeOutboxService outbox) {
        this.jdbc = jdbc;
        this.definitions = definitions;
        this.outbox = outbox;
    }

    @Transactional
    public ReplayResult replay(Long examId, Long taskId) {
        requireManage(examId);
        List<ReplayTarget> rows = jdbc.query(
            """
                select t.id task_id,t.status task_status,t.incident_id,t.replay_count,
                       s.id session_id,s.status session_status,
                       sub.id submission_id,sub.status submission_status,
                       fp.submission_id final_payload_id
                  from submission_timeout_task t
                  join exam_session s on s.id=t.session_id
                  join submission sub on sub.exam_id=s.exam_id and sub.student_id=s.student_id
                  left join submission_final_payload fp on fp.submission_id=sub.id
                 where t.id=? and t.exam_id=?
                 for update
                """,
            (rs, rowNum) -> new ReplayTarget(
                rs.getLong("task_id"), rs.getString("task_status"), rs.getString("incident_id"),
                rs.getInt("replay_count"), rs.getLong("session_id"),
                rs.getString("session_status"), rs.getLong("submission_id"),
                rs.getString("submission_status"), rs.getObject("final_payload_id", Long.class)
            ),
            taskId, examId
        );
        if (rows.isEmpty()) {
            throw new BusinessException("超时交卷任务不存在");
        }
        ReplayTarget target = rows.getFirst();
        if (!"FAILED".equals(target.taskStatus())
            || !"SUBMISSION_FAILED".equals(target.sessionStatus())
            || !"IN_PROGRESS".equals(target.submissionStatus())) {
            throw new BusinessException("当前状态不允许重放超时交卷任务");
        }
        if (target.finalPayloadId() != null || hasSubmissionAccepted(target.submissionId())) {
            throw new BusinessException("交卷已经形成最终结果，禁止重放");
        }
        int taskUpdated = jdbc.update(
            """
                update submission_timeout_task
                   set status='PENDING',due_at=least(due_at,current_timestamp(3)),
                       claim_token=null,lease_until=null,attempt_count=0,next_retry_at=null,
                       last_error=null,failure_code=null,incident_id=null,failed_at=null,
                       replay_count=replay_count+1,updated_at=current_timestamp(3)
                 where id=? and status='FAILED'
                """,
            target.taskId()
        );
        int sessionUpdated = jdbc.update(
            """
                update exam_session
                   set status='AUTO_SUBMITTING',claim_time=current_timestamp(3),
                       update_time=current_timestamp(3)
                 where id=? and status='SUBMISSION_FAILED'
                """,
            target.sessionId()
        );
        if (taskUpdated != 1 || sessionUpdated != 1) {
            throw new IllegalStateException("超时交卷任务重放状态冲突");
        }
        return new ReplayResult(target.incidentId(), target.replayCount() + 1);
    }

    private void requireManage(Long examId) {
        RuntimeExamDefinition definition = definitions.find(examId);
        if (definition == null) {
            throw new BusinessException("考试不存在");
        }
        if (!SecurityUtils.getCurrentRoles().contains("ADMIN")
            && !Objects.equals(definition.publisherId(), SecurityUtils.getCurrentUserId())) {
            throw new BusinessException("无权限重放该考试的超时交卷任务");
        }
    }

    private boolean hasSubmissionAccepted(Long submissionId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from outbox_event where id=?",
            Integer.class, outbox.submissionAcceptedEventId(submissionId)
        );
        return count != null && count > 0;
    }

    public record ReplayResult(String incidentId, int replayCount) {
    }

    private record ReplayTarget(Long taskId, String taskStatus, String incidentId, int replayCount,
                                Long sessionId,
                                String sessionStatus, Long submissionId,
                                String submissionStatus, Long finalPayloadId) {
    }
}
