package com.ekusys.exam.runtime.messaging;
import org.springframework.amqp.core.*;import org.springframework.context.annotation.*;
@Configuration public class RuntimeRabbitConfig{public static final String EXCHANGE="exam.events";public static final String QUEUE="exam.grading.submission-accepted";
 @Bean TopicExchange examEventsExchange(){return new TopicExchange(EXCHANGE,true,false);}@Bean Queue gradingQueue(){return QueueBuilder.durable(QUEUE).build();}@Bean Binding gradingBinding(TopicExchange examEventsExchange,Queue gradingQueue){return BindingBuilder.bind(gradingQueue).to(examEventsExchange).with("SubmissionAccepted");}}
