CREATE INDEX CONCURRENTLY IF NOT EXISTS index_accounts_on_custom_fields
    ON accounts USING gin (custom_fields jsonb_path_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS index_campaigns_on_custom_fields
    ON campaigns USING gin (custom_fields jsonb_path_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS index_contacts_on_custom_fields
    ON contacts USING gin (custom_fields jsonb_path_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS index_leads_on_custom_fields
    ON leads USING gin (custom_fields jsonb_path_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS index_opportunities_on_custom_fields
    ON opportunities USING gin (custom_fields jsonb_path_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS index_tasks_on_custom_fields
    ON tasks USING gin (custom_fields jsonb_path_ops);
