package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.springframework.boot.DefaultApplicationArguments;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class CustomFieldsBackfillRunnerTest {

    @Test
    void exitsZeroWhenVerificationPasses() {
        assertExitCode(new CustomFieldsBackfillReport(Map.of()), 0);
    }

    @Test
    void exitsOneWhenVerificationFindsDrift() {
        CustomFieldsBackfillReport.TableReport table =
            new CustomFieldsBackfillReport.TableReport(2, 0, 1, 0, Map.of());
        assertExitCode(new CustomFieldsBackfillReport(Map.of("accounts", table)), 1);
    }

    private void assertExitCode(CustomFieldsBackfillReport report, int expected) {
        CustomFieldsBackfillJob job = mock(CustomFieldsBackfillJob.class);
        when(job.run()).thenReturn(report);
        AtomicInteger observedExitCode = new AtomicInteger(-1);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            CustomFieldsBackfillRunner runner = new CustomFieldsBackfillRunner(job, context, observedExitCode::set);
            runner.run(new DefaultApplicationArguments(new String[0]));
            assertThat(runner.getExitCode()).isEqualTo(expected);
            assertThat(observedExitCode).hasValue(expected);
        }
    }
}
