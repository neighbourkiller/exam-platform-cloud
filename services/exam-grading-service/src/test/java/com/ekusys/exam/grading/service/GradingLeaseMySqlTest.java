package com.ekusys.exam.grading.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.grading.client.ContentGradingClient;
import com.ekusys.exam.grading.client.RuntimeGradingClient;
import com.ekusys.exam.grading.dto.QuestionBatchScoreRequest;
import com.ekusys.exam.grading.dto.SubjectiveScoreItem;
import com.ekusys.exam.grading.dto.SubjectiveScoreRequest;
import com.ekusys.exam.grading.messaging.GradingOutboxService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class GradingLeaseMySqlTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    private JdbcTemplate jdbc;
    private GradingLeaseService leases;
    private GradingService grading;
    private GradingOutboxService outbox;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration")
            .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(ds);
        leases = new GradingLeaseService(jdbc);
        outbox = mock(GradingOutboxService.class);
        var target = new GradingService(jdbc, mock(RuntimeGradingClient.class), mock(ContentGradingClient.class), outbox, new AnswerKeyService(jdbc, new com.fasterxml.jackson.databind.ObjectMapper()));
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
            new AnnotationTransactionAttributeSource()));
        grading = (GradingService) factory.getProxy();
        jdbc.update("""
            insert into grading_submission(id,runtime_submission_id,exam_id,student_id,paper_snapshot_id,
                status,submitted_at,pass_score) values(1,100,10,20,30,'PENDING_SUBJECTIVE',now(3),10)
            """);
        jdbc.update("""
            insert into grade_result(id,runtime_submission_id,objective_score,status)
            values(1,100,2,'PENDING_SUBJECTIVE')
            """);
        for (long id = 1; id <= 2; id++) {
            jdbc.update("""
                insert into grading_task(id,grading_submission_id,exam_id,student_id,question_id,answer_id,max_score,status)
                values(?,1,10,20,40,?,10,'PENDING')
                """, id, id);
        }
        login(11);
    }

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    private static void login(long teacher) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject("teacher")
            .claim("uid", teacher).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void concurrentTeachersHaveExactlyOneWinner() throws Exception {
        List<Boolean> results = race(() -> claimAs(11), () -> claimAs(12));
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
    }

    private boolean claimAs(long teacher) {
        login(teacher);
        try { leases.claim(1L); return true; }
        catch (BusinessException conflict) { return false; }
        finally { SecurityContextHolder.clearContext(); }
    }

    @Test
    void expiryTakeoverRejectsOldOwnerAndOldToken() {
        String old = leases.claim(1L).token();
        assertThrows(BusinessException.class, () -> leases.claim(1L));
        jdbc.update("update grading_task set lease_expires_at=timestampadd(second,-1,now(3)) where id=1");
        assertThrows(BusinessException.class, () -> leases.renew(1L, old));
        assertThrows(BusinessException.class, () -> score(1L, old));
        login(12);
        String current = leases.claim(1L).token();
        login(11);
        leases.release(1L, old);
        assertThrows(BusinessException.class, () -> leases.renew(1L, current));
        assertThrows(BusinessException.class, () -> score(1L, current));
        login(12);
        assertEquals(current, leases.renew(1L, current).token());
        score(1L, current);
        assertThrows(BusinessException.class, () -> score(1L, current));
        assertEquals(1, jdbc.queryForObject("select count(*) from subjective_grade", Integer.class));
    }

    @Test
    void releaseAllowsImmediateReclaimAndMissingLeaseCannotScore() {
        assertThrows(BusinessException.class, () -> score(1L, null));
        String token = leases.claim(1L).token();
        leases.release(1L, token);
        String next = leases.claim(1L).token();
        assertNotEquals(token, next);
        assertThrows(BusinessException.class, () -> score(1L, token));
    }

    @Test
    void batchConflictRollsBackEarlierScoresAndValidatesExam() {
        String first = leases.claim(1L).token();
        String second = leases.claim(2L).token();
        var request = new QuestionBatchScoreRequest();
        request.setExamId(10L);
        request.setSubmissionAnswerIds(List.of(1L, 2L));
        request.setScore(5);
        request.setLeaseTokens(Map.of(1L, first, 2L, "stale"));
        assertThrows(BusinessException.class, () -> grading.scoreQuestionAnswers(40L, request));
        assertEquals(0, jdbc.queryForObject("select count(*) from subjective_grade", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from grading_task where status='PENDING'", Integer.class));
        request.setLeaseTokens(Map.of(1L, first, 2L, second));
        request.setExamId(99L);
        assertThrows(BusinessException.class, () -> grading.scoreQuestionAnswers(40L, request));
        request.setExamId(10L);
        grading.scoreQuestionAnswers(40L, request);
        assertEquals(12, jdbc.queryForObject("select total_score from grade_result", Integer.class));
        verify(outbox).gradeCompleted(100L);
    }

    @Test
    void concurrentDifferentQuestionsProduceCompleteTotal() throws Exception {
        String first = leases.claim(1L).token();
        login(12);
        String second = leases.claim(2L).token();
        race(() -> { login(11); score(1L, first); return true; },
            () -> { login(12); score(2L, second); return true; });
        assertEquals(12, jdbc.queryForObject("select total_score from grade_result", Integer.class));
        assertEquals("GRADED", jdbc.queryForObject("select status from grade_result", String.class));
        verify(outbox, times(1)).gradeCompleted(100L);
    }

    @Test
    void concurrentDuplicateSubmitWritesOnlyOneGrade() throws Exception {
        String token = leases.claim(1L).token();
        Callable<Boolean> submit = () -> {
            login(11);
            try { score(1L, token); return true; }
            catch (BusinessException conflict) { return false; }
        };
        assertEquals(1, race(submit, submit).stream().filter(Boolean::booleanValue).count());
        assertEquals(1, jdbc.queryForObject("select count(*) from subjective_grade", Integer.class));
        assertEquals(7, jdbc.queryForObject("select total_score from grade_result", Integer.class));
    }

    private void score(Long answer, String token) {
        var item = new SubjectiveScoreItem();
        item.setSubmissionAnswerId(answer);
        item.setScore(5);
        item.setLeaseToken(token);
        var request = new SubjectiveScoreRequest();
        request.setScores(List.of(item));
        grading.scoreSubjective(100L, request);
    }

    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                try { return action.call(); }
                finally { SecurityContextHolder.clearContext(); }
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }
}
