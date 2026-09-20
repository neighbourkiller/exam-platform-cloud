package com.ekusys.exam.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JacksonConfigTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
    }

    @Test
    void shouldSerializeLongFieldsAsString() throws Exception {
        IdHolder holder = new IdHolder();
        holder.setId(2037438473056669697L);
        holder.setPaperId(9001L);

        String json = objectMapper.writeValueAsString(holder);
        JsonNode node = objectMapper.readTree(json);

        assertTrue(node.get("id").isTextual());
        assertEquals("2037438473056669697", node.get("id").asText());
        assertTrue(node.get("paperId").isTextual());
        assertEquals("9001", node.get("paperId").asText());
    }

    @Test
    void shouldDeserializeStringIdToLong() throws Exception {
        String json = "{\"id\":\"2037438473056669697\",\"paperId\":\"9001\"}";
        IdHolder holder = objectMapper.readValue(json, IdHolder.class);

        assertEquals(2037438473056669697L, holder.getId());
        assertEquals(9001L, holder.getPaperId());
    }

    static class IdHolder {

        private Long id;
        private Long paperId;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getPaperId() {
            return paperId;
        }

        public void setPaperId(Long paperId) {
            this.paperId = paperId;
        }
    }
}
