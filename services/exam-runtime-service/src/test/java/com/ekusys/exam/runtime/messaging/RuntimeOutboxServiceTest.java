package com.ekusys.exam.runtime.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RuntimeOutboxServiceTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void returnedMessageIsRetriedInsteadOfMarkedPublished() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("id")).thenReturn("evt-1");
        when(resultSet.getString("event_type")).thenReturn("TestEvent");
        when(resultSet.getString("payload_json")).thenReturn("{}");
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        });
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(
                new Message(new byte[0]),
                312,
                "NO_ROUTE",
                RuntimeRabbitConfig.EXCHANGE,
                "TestEvent"
            ));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(
            eq(RuntimeRabbitConfig.EXCHANGE),
            eq("TestEvent"),
            eq("{}"),
            any(CorrelationData.class)
        );
        RuntimeOutboxService service = new RuntimeOutboxService(jdbc, rabbit, new ObjectMapper());

        service.publish();

        verify(jdbc).update(contains("retry_count=retry_count+1"), eq("evt-1"));
        verify(jdbc, never()).update(contains("status='PUBLISHED'"), eq("evt-1"));
    }
}
