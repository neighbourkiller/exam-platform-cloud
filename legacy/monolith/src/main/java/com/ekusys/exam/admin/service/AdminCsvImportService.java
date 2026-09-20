package com.ekusys.exam.admin.service;

import com.ekusys.exam.common.exception.BusinessException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AdminCsvImportService {

    private static final int MAX_ROWS = 1000;

    public ParsedCsv parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("导入文件不能为空");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        char delimiter = filename.endsWith(".tsv") ? '\t' : ',';
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw new BusinessException("导入文件表头不能为空");
            }
            List<String> headers = parseLine(stripBom(headerLine), delimiter).stream()
                .map(String::trim)
                .toList();
            if (headers.isEmpty() || headers.stream().anyMatch(String::isBlank)) {
                throw new BusinessException("导入文件表头存在空字段");
            }

            List<CsvRow> rows = new ArrayList<>();
            String line;
            int rowNumber = 1;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.isBlank()) {
                    continue;
                }
                if (rows.size() >= MAX_ROWS) {
                    throw new BusinessException("单次导入最多支持 " + MAX_ROWS + " 行");
                }
                List<String> values = parseLine(line, delimiter);
                Map<String, String> data = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    String value = i < values.size() ? values.get(i).trim() : "";
                    data.put(headers.get(i), value);
                }
                rows.add(new CsvRow(rowNumber, data));
            }
            return new ParsedCsv(headers, rows);
        } catch (IOException ex) {
            throw new BusinessException("读取导入文件失败");
        }
    }

    private String stripBom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private List<String> parseLine(String line, char delimiter) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == delimiter && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    public record ParsedCsv(List<String> headers, List<CsvRow> rows) {
    }

    public record CsvRow(int rowNumber, Map<String, String> data) {
    }
}
