package com.fatfreecrm.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Associations the Rails export templates dereference (app/views/x/index.xls.builder, export_csv.rb). */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are intentionally retained by this repository."
)
public class ExportLookupRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ExportLookupRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@code item.tags} in tagging order. */
    public Map<Long, List<String>> tags(String taggableType, Collection<Long> ids) {
        Map<Long, List<String>> tags = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return tags;
        }
        jdbcTemplate.query(
            "SELECT tg.taggable_id, t.name FROM taggings tg JOIN tags t ON t.id = tg.tag_id "
                + "WHERE tg.taggable_type = :type AND tg.taggable_id IN (:ids) AND tg.context = 'tags' ORDER BY tg.id",
            new MapSqlParameterSource("type", taggableType).addValue("ids", ids),
            resultSet -> {
                tags.computeIfAbsent(resultSet.getLong(1), id -> new java.util.ArrayList<>())
                    .add(resultSet.getString(2));
            });
        return tags;
    }

    /** {@code User#name}: first name unless blank, else username. */
    public Map<Long, String> userNames(Collection<Long> ids) {
        return names("SELECT id, CASE WHEN coalesce(btrim(first_name), '') = '' THEN username ELSE first_name END "
            + "FROM users WHERE id IN (:ids)", ids);
    }

    public Map<Long, String> campaignNames(Collection<Long> ids) {
        return names("SELECT id, name FROM campaigns WHERE id IN (:ids)", ids);
    }

    /** {@code Lead#full_name}: {@code "#{first_name} #{last_name}"}. */
    public Map<Long, String> leadNames(Collection<Long> ids) {
        return names("SELECT id, coalesce(first_name, '') || ' ' || coalesce(last_name, '') FROM leads "
            + "WHERE id IN (:ids)", ids);
    }

    /** {@code Opportunity has_one :account, through: :account_opportunity}. */
    public Map<Long, String> opportunityAccountNames(Collection<Long> ids) {
        return names("SELECT ao.opportunity_id, a.name FROM account_opportunities ao "
            + "JOIN accounts a ON a.id = ao.account_id WHERE ao.opportunity_id IN (:ids) ORDER BY ao.id", ids);
    }

    /** {@code has_one :billing_address / :business_address, as: :addressable}. */
    public Map<Long, Map<String, Object>> addresses(String addressableType, String addressType, Collection<Long> ids) {
        Map<Long, Map<String, Object>> addresses = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return addresses;
        }
        jdbcTemplate.queryForList(
            "SELECT * FROM addresses WHERE addressable_type = :type AND address_type = :addressType "
                + "AND addressable_id IN (:ids) ORDER BY id",
            new MapSqlParameterSource("type", addressableType).addValue("addressType", addressType)
                .addValue("ids", ids))
            .forEach(row -> addresses.putIfAbsent(((Number) row.get("addressable_id")).longValue(), row));
        return addresses;
    }

    private Map<Long, String> names(String sql, Collection<Long> ids) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        jdbcTemplate.query(sql, new MapSqlParameterSource("ids", ids),
            resultSet -> {
                names.putIfAbsent(resultSet.getLong(1), resultSet.getString(2));
            });
        return names;
    }
}
