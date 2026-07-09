package com.ekusys.exam.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ekusys.exam.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class CsvImportParserTest {
    private final CsvImportParser parser = new CsvImportParser();

    @Test
    void parsesBomQuotedCommaAndEscapedQuote() {
        MockMultipartFile file = file("users.csv", "\uFEFFusername,realName\nuser01,\"张,\"\"三\"\"\"\n");

        CsvImportParser.ParsedCsv csv = parser.parse(file);

        assertThat(csv.headers()).containsExactly("username", "realName");
        assertThat(csv.rows()).hasSize(1);
        assertThat(csv.rows().getFirst().value("realName")).isEqualTo("张,\"三\"");
    }

    @Test
    void parsesTsvAndSkipsBlankLines() {
        MockMultipartFile file = file("courses.tsv", "id\tname\n\n1\tJava\n");

        CsvImportParser.ParsedCsv csv = parser.parse(file);

        assertThat(csv.rows()).hasSize(1);
        assertThat(csv.rows().getFirst().value("name")).isEqualTo("Java");
        assertThat(csv.rows().getFirst().rowNumber()).isEqualTo(3);
    }

    @Test
    void rejectsDuplicateHeadersAndUnclosedQuotes() {
        assertThatThrownBy(() -> parser.parse(file("bad.csv", "id,id\n1,2\n")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("导入文件表头存在重复字段");
        assertThatThrownBy(() -> parser.parse(file("bad.csv", "id,name\n1,\"Java\n")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("导入文件存在未闭合的引号");
    }

    @Test
    void limitsRowsToOneThousand() {
        StringBuilder content = new StringBuilder("id,name\n");
        for (int index = 0; index < 1001; index++) {
            content.append(index).append(",course").append(index).append('\n');
        }

        assertThatThrownBy(() -> parser.parse(file("large.csv", content.toString())))
            .isInstanceOf(BusinessException.class)
            .hasMessage("单次导入最多支持 1000 行");
    }

    private MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }
}
