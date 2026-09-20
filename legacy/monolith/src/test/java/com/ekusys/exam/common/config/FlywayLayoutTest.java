package com.ekusys.exam.common.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class FlywayLayoutTest {

    @Test
    void baseApplicationShouldUseFlywayAsOnlyInitializationEntry() throws Exception {
        String yaml = new String(
            new ClassPathResource("application.yaml").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8
        );

        assertTrue(yaml.contains("mode: never"));
        assertTrue(yaml.contains("locations: classpath:db/migration"));
        assertTrue(yaml.contains("baseline-version: 3"));
        assertTrue(yaml.contains("secret: ${JWT_SECRET:dev-only-exam-secret-key-32bytes!}"));
        assertTrue(yaml.contains("default-password: ${APP_DEFAULT_PASSWORD:Exam@2026}"));
    }

    @Test
    void devProfileShouldAppendDevSeedLocation() throws Exception {
        String yaml = new String(
            new ClassPathResource("application-dev.yaml").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8
        );

        assertTrue(yaml.contains("classpath:db/migration,classpath:db/dev-seed"));
        assertTrue(yaml.contains("out-of-order: true"));
        assertTrue(!yaml.contains("password123"));
    }

    @Test
    void legacySqlFilesShouldBeMarkedAsReferenceOnly() throws Exception {
        String schema = new String(
            new ClassPathResource("schema.sql").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8
        );
        String data = new String(
            new ClassPathResource("data.sql").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8
        );

        assertTrue(schema.contains("Legacy reference only."));
        assertTrue(data.contains("Legacy reference only."));
    }

    @Test
    void studentProfileShouldHaveUniqueStudentNoIndex() throws Exception {
        String schema = new String(
            new ClassPathResource("schema.sql").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8
        );
        String migration = new String(
            new ClassPathResource("db/migration/V12__add_unique_student_no_index.sql").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8
        );

        assertTrue(schema.contains("UNIQUE KEY uk_student_profile_student_no (student_no)"));
        assertTrue(migration.contains("UPDATE student_profile"));
        assertTrue(migration.contains("ADD UNIQUE KEY uk_student_profile_student_no (student_no)"));
    }
}
