package com.ekusys.exam.iam.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.iam.admin.dto.UserCreateRequest;
import com.ekusys.exam.importing.BulkImportResultView;
import com.ekusys.exam.importing.CsvImportParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

class IamUserImportServiceTest {
    @Test
    void dryRunValidatesRowsAndReportsDuplicateStudentNumber() {
        IamAdminService admin = mock(IamAdminService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("select id from sys_role where code=?", Long.class, "STUDENT"))
            .thenReturn(List.of(2L));
        IamUserImportService service = new IamUserImportService(new CsvImportParser(), admin, jdbc);
        MockMultipartFile file = csv("studentNo,realName,password,enrollmentYear,teachingClassIds\n"
            + "S001,张三,123456,2026,1001\nS001,李四,123456,2026,1002\n");

        BulkImportResultView result = service.importUsers(file, "STUDENT", true);

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.errors().getFirst().field()).isEqualTo("studentNo");
        ArgumentCaptor<UserCreateRequest> request = ArgumentCaptor.forClass(UserCreateRequest.class);
        verify(admin).validateCreateUser(request.capture());
        verify(admin, never()).createUser(request.capture());
        assertThat(request.getValue().getUsername()).isEqualTo("S001");
        assertThat(request.getValue().getTeachingClassIds()).containsExactly(1001L);
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "users.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }
}
