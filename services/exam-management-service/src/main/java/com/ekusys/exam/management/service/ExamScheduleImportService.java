package com.ekusys.exam.management.service;

import com.ekusys.exam.exam.dto.ExamCreateRequest;
import com.ekusys.exam.importing.BulkImportResultView;
import com.ekusys.exam.importing.BulkImportRowErrorView;
import com.ekusys.exam.importing.CsvImportParser;
import com.ekusys.exam.importing.CsvImportValues;
import com.ekusys.exam.importing.RowImportException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ExamScheduleImportService {
    private final CsvImportParser parser;
    private final ExamManagementService examService;

    public ExamScheduleImportService(CsvImportParser parser, ExamManagementService examService) {
        this.parser = parser;
        this.examService = examService;
    }

    public BulkImportResultView importSchedules(MultipartFile file, boolean dryRun) {
        CsvImportParser.ParsedCsv csv = parser.parse(file);
        List<BulkImportRowErrorView> errors = new ArrayList<>();
        int success = 0;
        for (CsvImportParser.CsvRow row : csv.rows()) {
            try {
                CsvImportValues.require(row, "name", "paperId", "startTime", "endTime",
                    "durationMinutes", "passScore", "targetClassIds");
                ExamCreateRequest request = buildRequest(row);
                boolean autoPublish = CsvImportValues.booleanValue(row.value("autoPublish"));
                if (dryRun) examService.validateCreate(request);
                else examService.createAndOptionallyPublish(request, autoPublish);
                success++;
            } catch (Exception exception) {
                errors.add(error(row, exception));
            }
        }
        return BulkImportResultView.of(csv.rows().size(), success, dryRun, errors);
    }

    private ExamCreateRequest buildRequest(CsvImportParser.CsvRow row) {
        ExamCreateRequest request = new ExamCreateRequest();
        request.setName(row.value("name"));
        request.setPaperId(CsvImportValues.longValue(row.value("paperId"), "paperId"));
        request.setStartTime(CsvImportValues.dateTime(row.value("startTime"), "startTime"));
        request.setEndTime(CsvImportValues.dateTime(row.value("endTime"), "endTime"));
        request.setDurationMinutes(CsvImportValues.intValue(row.value("durationMinutes"), "durationMinutes"));
        request.setPassScore(CsvImportValues.intValue(row.value("passScore"), "passScore"));
        request.setTargetClassIds(CsvImportValues.longList(row.value("targetClassIds"), "targetClassIds"));
        request.setProctoringLevel(CsvImportValues.nullable(row.value("proctoringLevel")));
        return request;
    }

    private BulkImportRowErrorView error(CsvImportParser.CsvRow row, Exception exception) {
        if (exception instanceof RowImportException value) {
            return new BulkImportRowErrorView(row.rowNumber(), value.getField(), value.getMessage(), value.getRawValue());
        }
        return new BulkImportRowErrorView(row.rowNumber(), null,
            exception.getMessage() == null ? "处理失败" : exception.getMessage(), null);
    }
}
