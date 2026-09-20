package com.ekusys.exam.common.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Bean
    public Jackson2JsonMessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter rabbitMessageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(rabbitMessageConverter);
        return rabbitTemplate;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory examSubmissionRabbitListenerContainerFactory(
        ConnectionFactory connectionFactory,
        Jackson2JsonMessageConverter rabbitMessageConverter
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(rabbitMessageConverter);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
            .maxRetries(3)
            .recoverer(new RejectAndDontRequeueRecoverer())
            .build());
        return factory;
    }

    @Bean
    public Declarables examSubmissionRabbitDeclarables(ExamSubmissionRabbitProperties properties) {
        DirectExchange exchange = new DirectExchange(properties.getExchange(), true, false);
        DirectExchange dlx = new DirectExchange(properties.getDlx(), true, false);

        Map<String, Object> queueArgs = new HashMap<>();
        queueArgs.put("x-dead-letter-exchange", properties.getDlx());
        Queue queue = new Queue(properties.getQueue(), true, false, false, queueArgs);
        Queue dlq = new Queue(properties.getDlq(), true);

        Binding binding = BindingBuilder.bind(queue).to(exchange).with(properties.getRoutingKey());
        Binding dlqBinding = BindingBuilder.bind(dlq).to(dlx).with(properties.getRoutingKey());

        return new Declarables(exchange, dlx, queue, dlq, binding, dlqBinding);
    }
}
