package com.ekusys.exam.importing;

import com.ekusys.exam.common.exception.BusinessException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class CsvImportParser {
    private static final int MAX_ROWS = 1000;

    public ParsedCsv parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("导入文件不能为空");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        char delimiter = filename.endsWith(".tsv") ? '\t' : ',';
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw new BusinessException("导入文件表头不能为空");
            }
            List<String> headers = parseLine(stripBom(headerLine), delimiter).stream().map(String::trim).toList();
            if (headers.isEmpty() || headers.stream().anyMatch(String::isBlank)) {
                throw new BusinessException("导入文件表头存在空字段");
            }
            if (headers.stream().distinct().count() != headers.size()) {
                throw new BusinessException("导入文件表头存在重复字段");
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
                for (int index = 0; index < headers.size(); index++) {
                    data.put(headers.get(index), index < values.size() ? values.get(index).trim() : "");
                }
                rows.add(new CsvRow(rowNumber, data));
            }
            return new ParsedCsv(headers, rows);
        } catch (IOException exception) {
            throw new BusinessException("读取导入文件失败");
        }
    }

    private String stripBom(String value) {
        return value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }

    private List<String> parseLine(String line, char delimiter) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (value == delimiter && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(value);
            }
        }
        if (quoted) {
            throw new BusinessException("导入文件存在未闭合的引号");
        }
        values.add(current.toString());
        return values;
    }

    public record ParsedCsv(List<String> headers, List<CsvRow> rows) {
    }

    public record CsvRow(int rowNumber, Map<String, String> data) {
        public String value(String field) {
            return data.getOrDefault(field, "");
        }
    }
}
