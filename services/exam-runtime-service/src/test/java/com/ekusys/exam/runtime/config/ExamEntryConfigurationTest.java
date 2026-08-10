package com.ekusys.exam.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class ExamEntryConfigurationTest {
    @Test
    void keepsPrimaryTemplateForExistingServicesAndReadCommittedTemplateForActivation() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PlatformTransactionManager.class,
                () -> mock(PlatformTransactionManager.class));
            context.register(ExamEntryConfiguration.class);
            context.refresh();

            assertThat(context.getBean(TransactionTemplate.class))
                .isSameAs(context.getBean("transactionTemplate"));
            TransactionTemplate activation = context.getBean(
                "examEntryActivationTransactionTemplate", TransactionTemplate.class
            );
            assertThat(activation.getIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(activation.getTimeout()).isEqualTo(5);
        }
    }
}
