package com.ekusys.exam.grading.service;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.outbox.OutboxEventWriter;
import com.ekusys.exam.content.api.*;
import com.ekusys.exam.grading.api.*;
import com.ekusys.exam.grading.client.*;
import com.ekusys.exam.grading.dto.*;
import com.ekusys.exam.grading.messaging.GradingOutboxService;
import com.ekusys.exam.management.api.ExamRegradeContext;
import com.ekusys.exam.runtime.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class RegradeMySqlTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withCommand("--log-bin-trust-function-creators=1");
    JdbcTemplate jdbc;
    AnswerKeyService keys;
    GradingService grading;
    RegradeService service;
    RegradeWorker worker;
    TransactionTemplate tx;
    RegradeClients.Management management;
    RegradeClients.Runtime runtime;
    RegradeClients.Content bank;
    RegradeClients.Reporting reporting;
    RuntimeGradingClient inputs;
    ContentGradingClient snapshots;
    PaperSnapshotView paper;
    static final String OPTIONS = "[{\"label\":\"A\",\"value\":\"甲\"},{\"label\":\"B\",\"value\":\"乙\"}]";

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean(); flyway.migrate();
        jdbc = new JdbcTemplate(ds);
        var manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        var mapper = new ObjectMapper().findAndRegisterModules();
        keys = new AnswerKeyService(jdbc, mapper);
        inputs = mock(RuntimeGradingClient.class); snapshots = mock(ContentGradingClient.class);
        management = mock(RegradeClients.Management.class); runtime = mock(RegradeClients.Runtime.class);
        bank = mock(RegradeClients.Content.class); reporting = mock(RegradeClients.Reporting.class);
        var outbox = new GradingOutboxService(jdbc, new OutboxEventWriter(jdbc, mapper));
        grading = new GradingService(jdbc, inputs, snapshots, outbox, keys);
        service = new RegradeService(jdbc, keys, snapshots, management, runtime, bank, manager);
        worker = new RegradeWorker(jdbc, keys, grading, inputs, snapshots, runtime, bank, reporting, manager, Runnable::run);
        paper = new PaperSnapshotView(30L, 40L, 1L, "考试", 50L, 10, List.of(question(1, "SINGLE", "A", 10)));
        when(snapshots.snapshot(30L)).thenAnswer(i -> ApiResponse.ok(paper));
        when(management.context(10L)).thenReturn(ApiResponse.ok(new ExamRegradeContext(10L, 11L, 30L, "PUBLISHED", LocalDateTime.now().minusHours(1))));
        when(runtime.page(eq(10L), anyLong(), any())).thenReturn(ApiResponse.ok(new SubmittedPage(List.of(100L,101L),101,2)));
        when(inputs.input(100L)).thenReturn(ApiResponse.ok(input(100, "A")));
        when(inputs.input(101L)).thenReturn(ApiResponse.ok(input(101, "B")));
        when(bank.context(1L)).thenReturn(ApiResponse.ok(new QuestionCorrectionView(1L,"SINGLE","题目",OPTIONS,"A","fingerprint",11L)));
        when(bank.correct(eq(1L), any())).thenReturn(ApiResponse.ok(new QuestionCorrectionResult("APPLIED", "已同步")));
        when(reporting.progress(any())).thenAnswer(i -> ApiResponse.ok(new GradeProjectionProgress(
            ((GradeProjectionQuery)i.getArgument(0)).revisions().stream().map(GradeProjectionQuery.Revision::submissionId).toList())));
        login(11);
    }
    @AfterEach void logout() { SecurityContextHolder.clearContext(); }
    static void login(long id) {
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject("teacher").claim("uid", id).claim("roles", List.of("TEACHER")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
    PaperSnapshotQuestion question(long id, String type, String answer, int score) {
        return new PaperSnapshotQuestion(id,type,"EASY","题目",OPTIONS,answer,"",score,(int)id);
    }
    GradingSubmissionInput input(long id, String answer) {
        return new GradingSubmissionInput(id,10L,id+1000,"考试",6,30L,LocalDateTime.now(),List.of(new GradingAnswerInput(id+2000,1L,answer)));
    }
    RegradeRequest request(long version, String key) { return new RegradeRequest(key,version,"答案录入错误",Map.of(1L,"B")); }
    int score(long sid) { return jdbc.queryForObject("select total_score from grade_result where runtime_submission_id=?",Integer.class,sid); }
    String state(long job) { return jdbc.queryForObject("select status from regrade_job where id=?",String.class,job); }

    @Test void regradeUpdatesBothDirectionsHandlesDelayedInitialGradingAndRestores() {
        tx.executeWithoutResult(s -> grading.processInput(input(100,"A"),paper));
        long job = service.create(10L,request(0,"one"),null);
        assertEquals(job,service.create(10L,request(0,"one"),null));
        worker.tick();
        assertEquals("COMPLETED",state(job));
        assertEquals(0,score(100)); assertEquals(10,score(101));
        assertEquals(0,jdbc.queryForObject("select pass_flag from grade_result where runtime_submission_id=100",Integer.class));
        assertEquals(2,jdbc.queryForObject("select count(*) from regrade_item where status='DONE' and projection_synced=1",Integer.class));
        assertEquals(3,jdbc.queryForObject("select count(*) from outbox_event",Integer.class));
        worker.tick();
        assertEquals(3,jdbc.queryForObject("select count(*) from outbox_event",Integer.class));
        assertThrows(BusinessException.class,() -> service.create(10L,request(0,"stale"),null));
        long restored = service.create(10L,new RegradeRequest("restore",1,"恢复原答案",Map.of()),0L);
        worker.tick();
        assertEquals("COMPLETED",state(restored));
        assertEquals(10,score(100)); assertEquals(0,score(101));
        assertEquals(1,jdbc.queryForObject("select count(*) from regrade_bank_sync",Integer.class));
        verify(bank,times(1)).correct(eq(1L),any());
        assertEquals("A",paper.questions().getFirst().answer());
        var history=service.history(10L,1);
        assertEquals(2,history.size());
        assertEquals(0L,history.getFirst().get("restored_from_version"));
        assertTrue(history.getFirst().get("previous_answers_json").toString().contains("B"));
    }
    @Test void bankPermissionFailureDoesNotBlockScoresAndOldSyncIsSuperseded() {
        when(bank.correct(eq(1L),any())).thenReturn(ApiResponse.ok(new QuestionCorrectionResult("FORBIDDEN","无权限")));
        long job=service.create(10L,request(0,"one"),null); worker.tick();
        assertEquals("COMPLETED",state(job)); assertEquals(10,score(101));
        assertEquals("FORBIDDEN",jdbc.queryForObject("select status from regrade_bank_sync",String.class));
        service.create(10L,new RegradeRequest("restore",1,"恢复",Map.of()),0L);
        assertEquals("SUPERSEDED",jdbc.queryForObject("select status from regrade_bank_sync",String.class));
    }
    @Test void failedInputsRetryWithoutDuplicateGradesAndRespectLease() {
        when(inputs.input(100L)).thenThrow(new IllegalStateException("offline"));
        long job=service.create(10L,request(0,"one"),null);
        jdbc.update("update regrade_job set lease_token='another',lease_until=timestampadd(minute,1,now(3)) where id=?",job);
        worker.tick(); assertEquals(0,jdbc.queryForObject("select count(*) from grade_result",Integer.class));
        jdbc.update("update regrade_job set lease_until=timestampadd(second,-1,now(3)) where id=?",job);
        for(int i=0;i<5;i++) worker.tick();
        assertEquals("FAILED",state(job)); assertEquals(10,score(101));
        assertEquals(1,jdbc.queryForObject("select count(*) from outbox_event",Integer.class));
        doReturn(ApiResponse.ok(input(100,"A"))).when(inputs).input(100L);
        service.retry(10L,job); worker.tick();
        assertEquals("COMPLETED",state(job));
        assertEquals(2,jdbc.queryForObject("select count(*) from outbox_event",Integer.class));
    }
    @Test void transactionFailureRollsBackItemGradeAndOutboxTogether() {
        long job=service.create(10L,request(0,"one"),null);
        jdbc.execute("CREATE TRIGGER reject_regrade BEFORE UPDATE ON regrade_item FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        worker.tick();
        assertEquals(0,jdbc.queryForObject("select count(*) from grade_result",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from outbox_event",Integer.class));
        jdbc.execute("DROP TRIGGER reject_regrade");
        worker.tick(); assertEquals("COMPLETED",state(job));
    }
    @Test void concurrentVersionCreationHasOneWinner() throws Exception {
        var barrier=new CountDownLatch(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<2;i++) { final int n=i; results.add(executor.submit(() -> {
                login(11); barrier.countDown(); barrier.await(10,TimeUnit.SECONDS);
                try { service.create(10L,request(0,"race"+n),null); return true; }
                catch(BusinessException expected) { return false; }
                finally { SecurityContextHolder.clearContext(); }
            })); }
            int winners=0; for(var result:results) if(result.get(20,TimeUnit.SECONDS)) winners++;
            assertEquals(1,winners);
        }
    }
    @Test void allObjectiveTypesKeepExistingJudgingRulesAndSubjectiveScores() {
        paper=new PaperSnapshotView(30L,40L,1L,"考试",50L,50,List.of(question(1,"SINGLE","A",10),
            question(2,"MULTI","A",10),question(3,"JUDGE","A",10),question(4,"BLANK","old",10),question(5,"SHORT","参考",10)));
        var input=new GradingSubmissionInput(100L,10L,1100L,"考试",26,30L,LocalDateTime.now(),List.of(
            new GradingAnswerInput(2001L,1L,"B"),new GradingAnswerInput(2002L,2L,"B,A"),
            new GradingAnswerInput(2003L,3L,"B"),new GradingAnswerInput(2004L,4L," NEW "),new GradingAnswerInput(2005L,5L,"作文")));
        tx.executeWithoutResult(s -> grading.processInput(input,paper));
        jdbc.update("insert into subjective_grade(id,grading_task_id,teacher_id,score,graded_at) select 1,id,11,7,now(3) from grading_task");
        jdbc.update("update grading_task set status='GRADED'");
        jdbc.update("update grade_result set subjective_score=7,total_score=7,status='GRADED'");
        when(inputs.input(100L)).thenReturn(ApiResponse.ok(input));
        when(runtime.page(eq(10L),anyLong(),any())).thenReturn(ApiResponse.ok(new SubmittedPage(List.of(100L),100,1)));
        long job=service.create(10L,new RegradeRequest("all",0,"客观题修正",Map.of(1L,"B",2L,"A,B",3L,"B",4L,"new")),null);
        worker.tick(); assertEquals("COMPLETED",state(job)); assertEquals(47,score(100));
        assertEquals(7,jdbc.queryForObject("select subjective_score from grade_result",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from subjective_grade",Integer.class));
    }
    @Test void subjectiveMarkingAndRegradeShareSubmissionLock() throws Exception {
        paper=new PaperSnapshotView(30L,40L,1L,"考试",50L,20,List.of(question(1,"SINGLE","A",10),question(2,"SHORT","参考",10)));
        tx.executeWithoutResult(s -> grading.processInput(input(100,"B"),paper));
        long answerId=jdbc.queryForObject("select answer_id from grading_task",Long.class);
        String token=new GradingLeaseService(jdbc).claim(answerId).token();
        var mark=new SubjectiveScoreItem(); mark.setSubmissionAnswerId(answerId); mark.setScore(7); mark.setLeaseToken(token);
        var request=new SubjectiveScoreRequest(); request.setScores(List.of(mark));
        when(runtime.page(eq(10L),anyLong(),any())).thenReturn(ApiResponse.ok(new SubmittedPage(List.of(100L),100,1)));
        when(inputs.input(100L)).thenReturn(ApiResponse.ok(input(100,"B")));
        long job=service.create(10L,request(0,"concurrent-mark"),null);
        var ready=new CountDownLatch(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var marking=executor.submit(() -> { login(11); ready.countDown(); ready.await(10,TimeUnit.SECONDS);
                try { tx.executeWithoutResult(s -> grading.scoreSubjective(100L,request)); }
                finally { SecurityContextHolder.clearContext(); } return true; });
            var regrading=executor.submit(() -> { ready.countDown(); ready.await(10,TimeUnit.SECONDS); worker.tick(); return true; });
            marking.get(20,TimeUnit.SECONDS); regrading.get(20,TimeUnit.SECONDS);
        }
        assertEquals("COMPLETED",state(job)); assertEquals(17,score(100));
        assertEquals(1,jdbc.queryForObject("select count(*) from subjective_grade",Integer.class));
        assertEquals("GRADED",jdbc.queryForObject("select status from grade_result",String.class));
    }
    @Test void expiredWorkerCannotCommitAfterAnotherOwnerTakesLease() throws Exception {
        long job=service.create(10L,request(0,"fenced"),null);
        var fetching=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(inputs.input(100L)).thenAnswer(i -> { fetching.countDown(); release.await(10,TimeUnit.SECONDS); return ApiResponse.ok(input(100,"A")); });
        try(var executor=Executors.newSingleThreadExecutor()) {
            var run=executor.submit(worker::tick);
            assertTrue(fetching.await(10,TimeUnit.SECONDS));
            jdbc.update("update regrade_job set lease_token='new-owner',lease_until=timestampadd(minute,1,now(3)) where id=?",job);
            release.countDown(); run.get(20,TimeUnit.SECONDS);
        }
        assertEquals(0,jdbc.queryForObject("select count(*) from grade_result",Integer.class));
        assertEquals("new-owner",jdbc.queryForObject("select lease_token from regrade_job",String.class));
        jdbc.update("update regrade_job set lease_until=timestampadd(second,-1,now(3))");
        worker.tick(); assertEquals("COMPLETED",state(job));
    }
    @Test void firstGradingRacingVersionActivationAlwaysConvergesToNewAnswer() throws Exception {
        var start=new CountDownLatch(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(() -> { start.countDown(); start.await(10,TimeUnit.SECONDS);
                tx.executeWithoutResult(s -> grading.processInput(input(100,"A"),paper)); return true; });
            var correction=executor.submit(() -> { login(11); start.countDown(); start.await(10,TimeUnit.SECONDS);
                try { return service.create(10L,request(0,"initial-race"),null); }
                finally { SecurityContextHolder.clearContext(); } });
            first.get(20,TimeUnit.SECONDS); long job=correction.get(20,TimeUnit.SECONDS);
            worker.tick(); assertEquals("COMPLETED",state(job)); assertEquals(0,score(100));
            assertEquals(1L,jdbc.queryForObject("select answer_version from grade_result where runtime_submission_id=100",Long.class));
        }
    }

    @Test void validatesAuthorityExamTimeAndOptions() {
        login(12); assertThrows(BusinessException.class,() -> service.answerKey(10L)); login(11);
        assertThrows(BusinessException.class,() -> service.create(10L,new RegradeRequest("bad",0,"bad",Map.of(1L,"Z")),null));
        when(management.context(10L)).thenReturn(ApiResponse.ok(new ExamRegradeContext(10L,11L,30L,"PUBLISHED",LocalDateTime.now().plusHours(1))));
        assertThrows(BusinessException.class,() -> service.create(10L,request(0,"early"),null));
    }
    @Test void pendingSubjectiveIsNotPublishedAsFinalGrade() {
        paper=new PaperSnapshotView(30L,40L,1L,"考试",50L,20,List.of(question(1,"SINGLE","A",10),question(2,"SHORT","参考",10)));
        long job=service.create(10L,request(0,"pending"),null); worker.tick();
        assertEquals("COMPLETED",state(job)); assertEquals(10,score(101));
        assertEquals(0,jdbc.queryForObject("select count(*) from outbox_event",Integer.class));
        assertEquals(2L,service.detail(10L,job,1).get("subjectivePending"));
    }
    @Test void unknownBankOutcomeRetainsIdempotencyKeyAndBlocksSupersedingUntilResolved() {
        when(bank.correct(eq(1L),any())).thenThrow(new IllegalStateException("lost response"));
        long job=service.create(10L,request(0,"unknown"),null); worker.tick();
        assertEquals("COMPLETED",state(job));
        String operation=jdbc.queryForObject("select operation_id from regrade_bank_sync",String.class);
        assertThrows(BusinessException.class,() -> service.create(10L,new RegradeRequest("restore",1,"恢复",Map.of()),0L));
        when(bank.correct(eq(1L),any())).thenReturn(ApiResponse.ok(new QuestionCorrectionResult("APPLIED","已经成功")));
        jdbc.update("update regrade_bank_sync set next_retry_at=now(3)"); worker.tick();
        assertEquals(operation,jdbc.queryForObject("select operation_id from regrade_bank_sync",String.class));
        service.create(10L,new RegradeRequest("restore",1,"恢复",Map.of()),0L);
    }
}
