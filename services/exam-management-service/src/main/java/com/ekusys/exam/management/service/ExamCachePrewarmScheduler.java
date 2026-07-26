package com.ekusys.exam.management.service;

import com.ekusys.exam.management.config.ExamCacheProperties;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExamCachePrewarmScheduler {
    private static final Logger log = LoggerFactory.getLogger(ExamCachePrewarmScheduler.class);

    private final JdbcTemplate jdbc;
    private final ExamManagementService examService;
    private final ExamCacheWarmService cacheWarmService;
    private final ExamCacheProperties properties;

    public ExamCachePrewarmScheduler(JdbcTemplate jdbc, ExamManagementService examService,
                                     ExamCacheWarmService cacheWarmService,
                                     ExamCacheProperties properties) {
        this.jdbc = jdbc;
        this.examService = examService;
        this.cacheWarmService = cacheWarmService;
        this.properties = properties;
    }

    @Scheduled(
        fixedDelayString = "${app.exam-cache.prewarm-scan-delay-millis:60000}",
        initialDelayString = "${app.exam-cache.prewarm-initial-delay-millis:10000}"
    )
    public void prewarmUpcomingExams() {
        if (!properties.isEnabled()) {
            return;
        }
        LocalDateTime now = jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
        if (now == null) {
            return;
        }
        int batchSize = properties.safePrewarmBatchSize();
        int offset = 0;
        while (true) {
            List<UpcomingExam> exams = upcoming(now, batchSize, offset);
            for (UpcomingExam exam : exams) {
                try {
                    examService.runtimeMetadata(exam.examId());
                    cacheWarmService.warmPaper(exam.examId(), exam.snapshotId());
                } catch (RuntimeException exception) {
                    log.warn("Exam cache prewarm item failed, next scan will retry: examId={}, snapshotId={}",
                        exam.examId(), exam.snapshotId(), exception);
                }
            }
            if (exams.size() < batchSize) {
                return;
            }
            offset += exams.size();
        }
    }

    private List<UpcomingExam> upcoming(LocalDateTime now, int batchSize, int offset) {
        return jdbc.query(
            """
                select e.id,r.paper_snapshot_id
                  from exam e
                  join exam_paper_ref r on r.exam_id=e.id
                 where e.status='PUBLISHED'
                   and e.start_time>=?
                   and e.start_time<=?
                   and e.end_time>?
                 order by e.start_time,e.id
                 limit ? offset ?
                """,
            (rs, rowNum) -> new UpcomingExam(rs.getLong("id"), rs.getLong("paper_snapshot_id")),
            now,
            now.plusMinutes(properties.safePrewarmAheadMinutes()),
            now,
            batchSize,
            offset
        );
    }

    private record UpcomingExam(Long examId, Long snapshotId) {
    }
}
