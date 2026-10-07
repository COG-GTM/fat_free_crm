package com.fatfreecrm.repository;

import com.fatfreecrm.service.json.RailsResource;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RailsRowRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RailsRowRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RailsRow> findByIds(RailsResource resource, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT * FROM " + resource.table() + " WHERE id IN (:ids)";
        return jdbcTemplate.query(sql, new MapSqlParameterSource("ids", ids), resultSet -> {
            ResultSetMetaData metadata = resultSet.getMetaData();
            List<RailsRow> rows = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, Object> columns = new LinkedHashMap<>();
                Map<String, String> typeNames = new LinkedHashMap<>();
                Long id = null;
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    String column = metadata.getColumnLabel(index);
                    Object value = resultSet.getObject(index);
                    columns.put(column, value);
                    typeNames.put(column, metadata.getColumnTypeName(index));
                    if (column.equals("id") && value instanceof Number number) {
                        id = number.longValue();
                    }
                }
                rows.add(new RailsRow(id, columns, typeNames));
            }
            return rows;
        });
    }
}
