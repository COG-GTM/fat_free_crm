package com.fatfreecrm.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "The injected JdbcTemplate is retained by this repository."
)
public class ActivityVisibilityRepository {

    private final JdbcTemplate jdbcTemplate;

    public ActivityVisibilityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Map<String, Object>> findAccessFields(String table, int id) {
        if (!table.matches("(accounts|campaigns|contacts|leads|opportunities)")) {
            throw new IllegalArgumentException("Unsupported access-controlled table");
        }
        return jdbcTemplate.query(
            "SELECT user_id, assigned_to, access FROM " + table + " WHERE id = ?",
            resultSet -> resultSet.next()
                ? Optional.of(Map.of(
                    "user_id",
                    resultSet.getObject("user_id") == null ? NullValue.INSTANCE : resultSet.getObject("user_id"),
                    "assigned_to", resultSet.getObject("assigned_to") == null
                        ? NullValue.INSTANCE : resultSet.getObject("assigned_to"),
                    "access", resultSet.getObject("access") == null ? NullValue.INSTANCE : resultSet.getObject("access")
                ))
                : Optional.empty(),
            id
        );
    }

    public boolean hasDirectPermission(String assetType, int assetId, long userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM permissions WHERE asset_type = ? AND asset_id = ? AND user_id = ?)",
            Boolean.class, assetType, assetId, userId));
    }

    public enum NullValue {
        INSTANCE
    }
}
