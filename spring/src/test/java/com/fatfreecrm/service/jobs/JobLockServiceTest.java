package com.fatfreecrm.service.jobs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fatfreecrm.config.JobsProperties;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JobLockServiceTest {

    @Test
    void doesNotAcquireLocksWhenRailsOwnsJobs() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        JobLockService service = new JobLockService(dataSource, new JobsOwner(new JobsProperties()));

        assertFalse(service.runExclusively(JobLockKey.DROPBOX_POLL, () -> {
            throw new AssertionError("work must not run in Rails mode");
        }));

        verify(dataSource, never()).getConnection();
    }
}
