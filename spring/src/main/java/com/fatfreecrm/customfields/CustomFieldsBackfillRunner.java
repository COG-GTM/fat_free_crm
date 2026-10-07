package com.fatfreecrm.customfields;

import java.util.function.IntConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("backfill-custom-fields")
public class CustomFieldsBackfillRunner implements ApplicationRunner, ExitCodeGenerator {

    private final CustomFieldsBackfillJob job;
    private final ConfigurableApplicationContext context;
    private final IntConsumer exitHandler;
    private int exitCode;

    @Autowired
    public CustomFieldsBackfillRunner(CustomFieldsBackfillJob job, ConfigurableApplicationContext context) {
        this(job, context, System::exit);
    }

    CustomFieldsBackfillRunner(
        CustomFieldsBackfillJob job,
        ConfigurableApplicationContext context,
        IntConsumer exitHandler
    ) {
        this.job = job;
        this.context = context;
        this.exitHandler = exitHandler;
    }

    @Override
    public void run(ApplicationArguments args) {
        exitCode = job.run().ok() ? 0 : 1;
        exitHandler.accept(SpringApplication.exit(context, this));
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
