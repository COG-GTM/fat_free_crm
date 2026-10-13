package com.fatfreecrm.service.settings;

import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * AB-273 (settings-i18n): runs {@link SettingsJsonConversion} and exits. Activated only by the
 * {@code convert-settings-json} profile, which also disables the web server
 * (application-convert-settings-json.yml). Exits non-zero when any row failed or the run was
 * refused.
 */
@Component
@Profile("convert-settings-json")
public class SettingsJsonConversionRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsJsonConversionRunner.class);

    private final SettingsJsonConversion conversion;
    private final ConfigurableApplicationContext context;
    private final IntConsumer exitHandler;
    private int exitCode;

    @Autowired
    public SettingsJsonConversionRunner(SettingsJsonConversion conversion, ConfigurableApplicationContext context) {
        this(conversion, context, System::exit);
    }

    SettingsJsonConversionRunner(
        SettingsJsonConversion conversion,
        ConfigurableApplicationContext context,
        IntConsumer exitHandler
    ) {
        this.conversion = conversion;
        this.context = context;
        this.exitHandler = exitHandler;
    }

    @Override
    public void run(ApplicationArguments args) {
        SettingsJsonConversion.Report report = conversion.run();
        if (report.refusal() != null) {
            LOG.error("Conversion refused: {}", report.refusal());
            exitCode = 1;
        } else {
            LOG.info(
                "settings conversion report: total={} alreadyJson={} converted={} "
                    + "skippedConcurrent={} failed={} dryRun={}",
                report.total(), report.alreadyJson(), report.converted(),
                report.skippedConcurrent(), report.failed().size(), report.dryRun());
            report.failed().forEach(failure ->
                LOG.warn("failed settings row id={}: {}", failure.id(), failure.reason()));
            exitCode = report.failed().isEmpty() ? 0 : 1;
        }
        exitHandler.accept(SpringApplication.exit(context, this));
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
