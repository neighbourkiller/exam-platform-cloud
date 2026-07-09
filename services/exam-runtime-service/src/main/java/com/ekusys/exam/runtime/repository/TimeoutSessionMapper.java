package com.ekusys.exam.runtime.repository;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface TimeoutSessionMapper {
    @Select("""
        SELECT id, exam_id, student_id, deadline_time
          FROM exam_session
         WHERE ((status = 'ANSWERING' AND deadline_time <= CURRENT_TIMESTAMP(3))
             OR (status = 'AUTO_SUBMITTING' AND claim_time < CURRENT_TIMESTAMP(3) - INTERVAL 60 SECOND))
           AND MOD(id, #{shardTotal}) = #{shardIndex}
         ORDER BY deadline_time, id
         LIMIT #{limit}
        """)
    List<TimeoutSessionRow> findClaimable(@Param("shardIndex") int shardIndex,
                                           @Param("shardTotal") int shardTotal,
                                           @Param("limit") int limit);

    @Update("""
        UPDATE exam_session
           SET status = 'AUTO_SUBMITTING', claim_time = CURRENT_TIMESTAMP(3), update_time = CURRENT_TIMESTAMP(3)
         WHERE id = #{id}
           AND ((status = 'ANSWERING' AND deadline_time <= CURRENT_TIMESTAMP(3))
             OR (status = 'AUTO_SUBMITTING' AND claim_time < CURRENT_TIMESTAMP(3) - INTERVAL 60 SECOND))
        """)
    int claim(Long id);

    @Insert("""
        INSERT INTO submission(id, exam_id, student_id, status, submitted_at, timeout_submit, create_time, update_time)
        VALUES(#{id}, #{examId}, #{studentId}, 'PROCESSING', CURRENT_TIMESTAMP(3), 1,
               CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
        ON DUPLICATE KEY UPDATE status='PROCESSING', submitted_at=CURRENT_TIMESTAMP(3), timeout_submit=1,
                                update_time=CURRENT_TIMESTAMP(3)
        """)
    int createSubmission(@Param("id") Long id, @Param("examId") Long examId,
                         @Param("studentId") Long studentId);

    @Update("""
        UPDATE exam_session
           SET status = 'SUBMITTED', end_time = CURRENT_TIMESTAMP(3), claim_time = NULL,
               active_client_id = NULL, active_client_token = NULL,
               active_client_lease_until = NULL, active_client_last_seen = NULL,
               update_time = CURRENT_TIMESTAMP(3)
          WHERE id = #{id} AND status = 'AUTO_SUBMITTING'
        """)
    int markSubmitted(Long id);

    @Select("select id from submission where exam_id=#{examId} and student_id=#{studentId}")
    Long findSubmissionId(@Param("examId") Long examId, @Param("studentId") Long studentId);
}
