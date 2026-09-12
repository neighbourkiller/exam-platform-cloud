package com.ekusys.exam.runtime.service;

import com.ekusys.exam.runtime.client.ManagementRuntimeClient;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Testcontainers
class RegradeSubmissionPageMySqlTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4");
    @Test void onlyFinalizedExamSubmissionsArePagedAndUpperBoundIsStable() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        var jdbc=new JdbcTemplate(ds);
        var service=new ExamRuntimeService(jdbc,mock(ManagementRuntimeClient.class),new ObjectMapper(),
            mock(RuntimeOutboxService.class),mock(TimeoutSubmissionService.class),mock(ExamSnapshotService.class),
            mock(ExamClientLeaseService.class),mock(ExamAnswerInputValidator.class),mock(TimeoutTaskRepository.class),
            mock(ManualSubmissionService.class),mock(SubmissionFinalPayloadService.class),mock(SubmissionStatusProjectionService.class),mock(TransactionTemplate.class));
        for(long id=1;id<=101;id++) jdbc.update("""
            insert into submission(id,exam_id,student_id,status,paper_snapshot_id,submitted_at)
            values(?,10,?,'PROCESSING',30,now(3))
            """,id,id);
        jdbc.update("insert into submission(id,exam_id,student_id,status,paper_snapshot_id) values(200,10,200,'IN_PROGRESS',30)");
        jdbc.update("insert into submission(id,exam_id,student_id,status,paper_snapshot_id,submitted_at) values(300,11,300,'PROCESSING',30,now(3))");
        var first=service.submittedPage(10L,0,null);
        assertEquals(101,first.upperBound()); assertEquals(101,first.total()); assertEquals(100,first.submissionIds().size());
        jdbc.update("insert into submission(id,exam_id,student_id,status,paper_snapshot_id,submitted_at) values(400,10,400,'PROCESSING',30,now(3))");
        var last=service.submittedPage(10L,first.submissionIds().getLast(),first.upperBound());
        assertEquals(java.util.List.of(101L),last.submissionIds());
        assertEquals(0,service.submittedPage(99L,0,null).total());
    }
}
