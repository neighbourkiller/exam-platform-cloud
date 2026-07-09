package com.ekusys.exam.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ekusys.exam.exam.dto.ExamCreateRequest;
import com.ekusys.exam.importing.BulkImportResultView;
import com.ekusys.exam.importing.CsvImportParser;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ExamScheduleImportServiceTest {
    @Test
    void dryRunValidatesWithoutCreatingOrPublishing() {
        ExamManagementService exams = mock(ExamManagementService.class);
        ExamScheduleImportService service = new ExamScheduleImportService(new CsvImportParser(), exams);
        MockMultipartFile file = new MockMultipartFile("file", "exams.csv", "text/csv", (
            "name,paperId,startTime,endTime,durationMinutes,passScore,targetClassIds,autoPublish,proctoringLevel\n"
                + "期中考试,1,2027-05-01 09:00:00,2027-05-01 11:00:00,120,60,1001,true,STRICT\n"
        ).getBytes(StandardCharsets.UTF_8));

        BulkImportResultView result = service.importSchedules(file, true);

        assertThat(result.successCount()).isEqualTo(1);
        verify(exams).validateCreate(any(ExamCreateRequest.class));
        verify(exams, never()).createAndOptionallyPublish(any(), any(Boolean.class));
    }
}
