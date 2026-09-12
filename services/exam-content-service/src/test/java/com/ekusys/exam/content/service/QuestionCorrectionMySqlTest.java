package com.ekusys.exam.content.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.content.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class QuestionCorrectionMySqlTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    JdbcTemplate jdbc;
    QuestionCorrectionService service;
    TransactionTemplate tx;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        var flyway=Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean(); flyway.migrate();
        jdbc=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        service=new QuestionCorrectionService(jdbc,new ObjectMapper());
        jdbc.update("insert into question(id,subject_id,type,difficulty,content,answer,creator_id) values(1,2,'SINGLE','EASY','题干','A',11)");
    }
    QuestionCorrectionResult apply(String operation,long actor,boolean admin,String fingerprint,String answer) {
        return tx.execute(status -> service.correct(1L,new QuestionCorrectionCommand(operation,actor,admin,fingerprint,answer)));
    }
    @Test void retryAfterLostResponseReturnsReceiptWithoutOverwritingNewerAnswer() {
        String before=service.view(1L).fingerprint();
        assertEquals("APPLIED",apply("one",11,false,before,"B").status());
        String next=service.view(1L).fingerprint();
        assertEquals("APPLIED",apply("two",11,false,next,"C").status());
        assertEquals("APPLIED",apply("one",11,false,before,"B").status());
        assertEquals("C",service.view(1L).answer());
        assertEquals(2,jdbc.queryForObject("select count(*) from question_answer_correction",Integer.class));
        assertThrows(BusinessException.class,() -> apply("one",11,false,before,"D"));
    }
    @Test void permissionsConflictDeletionAndAdminAreEnforced() {
        String fingerprint=service.view(1L).fingerprint();
        assertEquals("FORBIDDEN",apply("denied",12,false,fingerprint,"B").status());
        jdbc.update("update question set content='新题干' where id=1");
        assertEquals("CONFLICT",apply("conflict",11,false,fingerprint,"B").status());
        assertEquals("APPLIED",apply("admin",12,true,service.view(1L).fingerprint(),"B").status());
        jdbc.update("delete from question where id=1");
        assertEquals("DELETED",apply("deleted",11,false,fingerprint,"B").status());
    }
    @Test void answerAndReceiptRollbackTogether() {
        String fingerprint=service.view(1L).fingerprint();
        assertThrows(IllegalStateException.class,() -> tx.execute(status -> {
            service.correct(1L,new QuestionCorrectionCommand("rollback",11L,false,fingerprint,"B"));
            throw new IllegalStateException("test rollback");
        }));
        assertEquals("A",service.view(1L).answer());
        assertEquals(0,jdbc.queryForObject("select count(*) from question_answer_correction",Integer.class));
    }
}
