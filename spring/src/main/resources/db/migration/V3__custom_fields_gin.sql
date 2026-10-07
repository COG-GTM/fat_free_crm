DROP INDEX CONCURRENTLY IF EXISTS index_accounts_on_custom_fields;
CREATE INDEX CONCURRENTLY index_accounts_on_custom_fields
    ON accounts USING gin (custom_fields jsonb_path_ops);
DROP INDEX CONCURRENTLY IF EXISTS index_campaigns_on_custom_fields;
CREATE INDEX CONCURRENTLY index_campaigns_on_custom_fields
    ON campaigns USING gin (custom_fields jsonb_path_ops);
DROP INDEX CONCURRENTLY IF EXISTS index_contacts_on_custom_fields;
CREATE INDEX CONCURRENTLY index_contacts_on_custom_fields
    ON contacts USING gin (custom_fields jsonb_path_ops);
DROP INDEX CONCURRENTLY IF EXISTS index_leads_on_custom_fields;
CREATE INDEX CONCURRENTLY index_leads_on_custom_fields
    ON leads USING gin (custom_fields jsonb_path_ops);
DROP INDEX CONCURRENTLY IF EXISTS index_opportunities_on_custom_fields;
CREATE INDEX CONCURRENTLY index_opportunities_on_custom_fields
    ON opportunities USING gin (custom_fields jsonb_path_ops);
DROP INDEX CONCURRENTLY IF EXISTS index_tasks_on_custom_fields;
CREATE INDEX CONCURRENTLY index_tasks_on_custom_fields
    ON tasks USING gin (custom_fields jsonb_path_ops);

DO $$
DECLARE
    invalid_indexes text;
BEGIN
    SELECT string_agg(expected.index_name, ', ' ORDER BY expected.index_name)
    INTO invalid_indexes
    FROM (VALUES
        ('index_accounts_on_custom_fields'),
        ('index_campaigns_on_custom_fields'),
        ('index_contacts_on_custom_fields'),
        ('index_leads_on_custom_fields'),
        ('index_opportunities_on_custom_fields'),
        ('index_tasks_on_custom_fields')
    ) AS expected(index_name)
    WHERE NOT EXISTS (
        SELECT 1
        FROM pg_class index_class
        JOIN pg_namespace index_schema ON index_schema.oid = index_class.relnamespace
        JOIN pg_index index_info ON index_info.indexrelid = index_class.oid
        WHERE index_class.relname = expected.index_name
            AND index_schema.nspname = current_schema()
            AND index_info.indisvalid
    );

    IF invalid_indexes IS NOT NULL THEN
        RAISE EXCEPTION 'Custom-field GIN indexes are missing or invalid: %', invalid_indexes;
    END IF;
END;
$$;
