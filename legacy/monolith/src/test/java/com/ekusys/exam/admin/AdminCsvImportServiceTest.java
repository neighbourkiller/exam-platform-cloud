package com.ekusys.exam.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ekusys.exam.admin.service.AdminCsvImportService;
import com.ekusys.exam.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class AdminCsvImportServiceTest {

    private final AdminCsvImportService service = new AdminCsvImportService();

    @Test
    void parseShouldHandleBomAndQuotedCsv() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "students.csv",
            "text/csv",
            ("\uFEFFusername,realName,teachingClassIds\n"
                + "s001,张三,\"1001,1002\"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        AdminCsvImportService.ParsedCsv csv = service.parse(file);

        assertEquals(1, csv.rows().size());
        assertEquals(2, csv.rows().getFirst().rowNumber());
        assertEquals("s001", csv.rows().getFirst().data().get("username"));
        assertEquals("1001,1002", csv.rows().getFirst().data().get("teachingClassIds"));
    }

    @Test
    void parseShouldRejectEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]);

        assertThrows(BusinessException.class, () -> service.parse(file));
    }
}
