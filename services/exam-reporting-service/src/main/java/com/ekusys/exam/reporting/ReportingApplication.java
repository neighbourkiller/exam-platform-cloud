package com.ekusys.exam.reporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.ekusys.exam")
public class ReportingApplication {
    public static void main(String[] args) { SpringApplication.run(ReportingApplication.class, args); }
}
