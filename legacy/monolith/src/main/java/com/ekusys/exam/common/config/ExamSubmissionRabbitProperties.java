package com.ekusys.exam.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.rabbitmq.exam-submission")
public class ExamSubmissionRabbitProperties {

    private String exchange = "exam.submission.exchange";
    private String queue = "exam.submission.process.queue";
    private String routingKey = "exam.submission.process";
    private String dlx = "exam.submission.dlx";
    private String dlq = "exam.submission.process.dlq";

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }

    public String getDlx() {
        return dlx;
    }

    public void setDlx(String dlx) {
        this.dlx = dlx;
    }

    public String getDlq() {
        return dlq;
    }

    public void setDlq(String dlq) {
        this.dlq = dlq;
    }
}
