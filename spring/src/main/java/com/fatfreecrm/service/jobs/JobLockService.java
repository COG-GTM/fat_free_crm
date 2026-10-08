package com.fatfreecrm.service.jobs;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed datasource is intentionally retained by this service."
)
public class JobLockService {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobLockService.class);
    private static final int LOCK_CLASS_ID = 1179009869;

    private final DataSource dataSource;
    private final JobsOwner jobsOwner;

    public JobLockService(DataSource dataSource, JobsOwner jobsOwner) {
        this.dataSource = dataSource;
        this.jobsOwner = jobsOwner;
    }

    public boolean runExclusively(JobLockKey key, Runnable work) {
        if (!jobsOwner.isSpring()) {
            LOGGER.warn("jobs owner is rails; skipping {}", key);
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection, key)) {
                LOGGER.info("skipped: lock held elsewhere ({})", key);
                return false;
            }
            LOGGER.info("lock acquired ({})", key);
            try {
                work.run();
            } finally {
                unlock(connection, key);
            }
            return true;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to use PostgreSQL job lock " + key, exception);
        }
    }

    private boolean tryLock(Connection connection, JobLockKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
            statement.setInt(1, LOCK_CLASS_ID);
            statement.setInt(2, key.objectId());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private void unlock(Connection connection, JobLockKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
            statement.setInt(1, LOCK_CLASS_ID);
            statement.setInt(2, key.objectId());
            statement.execute();
        }
    }
}
