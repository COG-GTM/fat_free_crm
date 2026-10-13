package com.fatfreecrm.service.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.config.JobsProperties;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class SolidQueueDrainServiceTest {

    @Test
    void drainsSupportedJobsAndRecordsSuccess() throws Exception {
        JdbcTemplate jdbc = jdbcWithSupportedJob();
        SolidQueueJobExecutor executor = mock(SolidQueueJobExecutor.class);
        SolidQueueDrainService service = service(jdbc, executor);

        assertEquals(1, service.drain());

        verify(executor).execute("AccountWebsiteJob", "{}");
        verify(jdbc).update(contains("UPDATE solid_queue_jobs SET finished_at"), any(), any(), eq(41L));
    }

    @Test
    void leavesUnsupportedJobsUnclaimed() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.<Long>query(anyString(), ArgumentMatchers.<RowMapper<Long>>any(), any(Object[].class)))
            .thenReturn(List.of());
        SolidQueueJobExecutor executor = mock(SolidQueueJobExecutor.class);

        assertEquals(0, service(jdbc, executor).drain());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).<Long>query(
            sql.capture(), ArgumentMatchers.<RowMapper<Long>>any(), any(Object[].class));
        assertTrue(sql.getAllValues().get(0).contains("solid_queue_claimed_executions"));
        assertTrue(sql.getAllValues().get(1).contains("class_name IN"));
        verify(executor, never()).execute(anyString(), anyString());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void recordsFailedExecutions() throws Exception {
        JdbcTemplate jdbc = jdbcWithSupportedJob();
        SolidQueueJobExecutor executor = mock(SolidQueueJobExecutor.class);
        doThrow(new IllegalStateException("failed")).when(executor).execute("AccountWebsiteJob", "{}");

        assertEquals(1, service(jdbc, executor).drain());

        verify(jdbc).update(contains("INSERT INTO solid_queue_failed_executions"),
            eq(41L), contains("failed"), any());
    }

    private static JdbcTemplate jdbcWithSupportedJob() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper<Object> mapper = invocation.getArgument(1);
            if (sql.contains("solid_queue_claimed_executions")) {
                return List.of();
            }
            ResultSet row = mock(ResultSet.class);
            if (sql.contains("FOR UPDATE")) {
                when(row.getLong("id")).thenReturn(41L);
                when(row.getString("class_name")).thenReturn("AccountWebsiteJob");
                when(row.getString("arguments")).thenReturn("{}");
            } else {
                when(row.getLong(1)).thenReturn(41L);
            }
            return List.of(mapper.mapRow(row, 0));
        }).when(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        return jdbc;
    }

    private static SolidQueueDrainService service(JdbcTemplate jdbc, SolidQueueJobExecutor executor) {
        JobsProperties properties = new JobsProperties();
        properties.setOwner("spring");
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new SolidQueueDrainService(
            jdbc, new ObjectMapper(), executor, new JobsOwner(properties), properties, transactionManager);
    }
}
