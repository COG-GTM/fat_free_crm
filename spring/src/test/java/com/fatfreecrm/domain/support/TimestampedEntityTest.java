package com.fatfreecrm.domain.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Setting;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class TimestampedEntityTest {

    private static final Instant CREATED = Instant.parse("2025-01-02T03:04:05.123456Z");
    private static final Instant UPDATED = Instant.parse("2025-01-02T03:04:06.654321Z");

    @Test
    void prePersistFillsBothTimestampsWithOneMicrosecondInstant() {
        Account account = new Account();
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        account.initializeTimestamps();

        assertThat(account.getCreatedAt()).isNotNull().isEqualTo(account.getUpdatedAt());
        assertThat(account.getCreatedAt()).isBetween(before, Instant.now());
        assertThat(account.getCreatedAt().getNano() % 1000).isZero();
    }

    @Test
    void prePersistKeepsTimestampsCopiedFromRailsRows() {
        Setting setting = new Setting();
        setting.setCreatedAt(CREATED);
        setting.setUpdatedAt(UPDATED);

        setting.initializeTimestamps();

        assertThat(setting.getCreatedAt()).isEqualTo(CREATED);
        assertThat(setting.getUpdatedAt()).isEqualTo(UPDATED);
    }

    @Test
    void prePersistOnlyFillsTheTimestampThatIsMissing() {
        Setting createdOnly = new Setting();
        createdOnly.setCreatedAt(CREATED);
        createdOnly.initializeTimestamps();
        assertThat(createdOnly.getCreatedAt()).isEqualTo(CREATED);
        assertThat(createdOnly.getUpdatedAt()).isAfter(UPDATED);

        Setting updatedOnly = new Setting();
        updatedOnly.setUpdatedAt(UPDATED);
        updatedOnly.initializeTimestamps();
        assertThat(updatedOnly.getUpdatedAt()).isEqualTo(UPDATED);
        assertThat(updatedOnly.getCreatedAt()).isAfter(UPDATED);
    }

    @Test
    void preUpdateReplacesUpdatedAtAndLeavesCreatedAtAlone() {
        Comment comment = new Comment();
        comment.setCreatedAt(CREATED);
        comment.setUpdatedAt(UPDATED);

        comment.updateTimestamp();

        assertThat(comment.getCreatedAt()).isEqualTo(CREATED);
        assertThat(comment.getUpdatedAt()).isAfter(UPDATED);
        assertThat(comment.getUpdatedAt().getNano() % 1000).isZero();
    }
}
