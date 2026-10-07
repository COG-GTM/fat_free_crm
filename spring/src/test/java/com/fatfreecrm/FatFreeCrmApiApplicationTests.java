package com.fatfreecrm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;

@ExtendWith(OutputCaptureExtension.class)
class FatFreeCrmApiApplicationTests extends AbstractPostgresIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void applicationLogsUseEcsJson(CapturedOutput output) {
        Logger logger = LoggerFactory.getLogger(FatFreeCrmApiApplicationTests.class);
        logger.info("ecs-format-verification");

        assertThat(output.getOut())
            .contains("\"@timestamp\"")
            .contains("\"ecs\":{\"version\":\"")
            .contains("\"service\":{\"name\":\"fat-free-crm-api\"")
            .contains("ecs-format-verification");
    }
}
