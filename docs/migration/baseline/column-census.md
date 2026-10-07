# Custom field (`cf_*`) column census

- Generated: 2026-10-07T18:48:12Z
- Adapter: PostgreSQL
- Schema version: 20260413041448
- Row counts included: true


## Summary

| Metric | Value |
|---|---|
| tables | 6 |
| custom_columns | 20 |
| mapped | 18 |
| orphaned | 2 |
| missing_columns | 1 |
| unattached_fields | 1 |
| type_mismatches | 1 |
| yaml_serialized | 1 |
| field type `string` | 2 |
| field type `email` | 1 |
| field type `select` | 3 |
| field type `decimal` | 2 |
| field type `date` | 1 |
| field type `text` | 2 |
| field type `check_boxes` | 1 |
| field type `boolean` | 1 |
| field type `url` | 1 |
| field type `integer` | 1 |
| field type `datetime` | 2 |
| field type `float` | 2 |
| sql type `character varying` | 8 |
| sql type `numeric(15,2)` | 2 |
| sql type `date` | 1 |
| sql type `text` | 3 |
| sql type `boolean` | 1 |
| sql type `integer` | 2 |
| sql type `timestamp without time zone` | 2 |
| sql type `double precision` | 1 |


## Account (`accounts`, 10 rows)

| Column | Status | SQL type | Field type | Label | Populated |
|---|---|---|---|---|---|
| `cf_account_code` | mapped | `character varying` | string | Account code | 3 |
| `cf_account_email` | mapped | `character varying` | email | Account email | 3 |
| `cf_account_segment` | mapped | `character varying` | select | Account segment | 3 |
| `cf_annual_value` | mapped | `numeric(15,2)` | decimal | Annual value | 3 |


## Campaign (`campaigns`, 10 rows)

| Column | Status | SQL type | Field type | Label | Populated |
|---|---|---|---|---|---|
| `cf_campaign_channel` | mapped | `character varying` | select | Campaign channel | 3 |
| `cf_campaign_launch_date` | mapped | `date` | date | Campaign launch date | 3 |
| `cf_campaign_notes` | mapped | `text` | text | Campaign notes | 3 |


## Contact (`contacts`, 10 rows)

| Column | Status | SQL type | Field type | Label | Populated |
|---|---|---|---|---|---|
| `cf_interests` | mapped (YAML) | `text` | check_boxes | Interests | 3 |
| `cf_legacy_region` | orphaned | `character varying` | — | — | 3 |
| `cf_marketing_opt_in` | mapped | `boolean` | boolean | Marketing opt in | 3 |
| `cf_profile_url` | mapped | `character varying` | url | Profile url | 3 |


## Lead (`leads`, 10 rows)

| Column | Status | SQL type | Field type | Label | Populated |
|---|---|---|---|---|---|
| `cf_estimated_users` | mapped | `integer` | integer | Estimated users | 3 |
| `cf_follow_up_at` | mapped | `timestamp without time zone` | datetime | Follow up at | 3 |
| `cf_partner_code` | missing_column | — | string | Partner code | — |


## Opportunity (`opportunities`, 10 rows)

| Column | Status | SQL type | Field type | Label | Populated |
|---|---|---|---|---|---|
| `cf_confidence_score` | mapped (type mismatch) | `integer` | float | Confidence score | 3 |
| `cf_forecast_amount` | mapped | `numeric(15,2)` | decimal | Forecast amount | 3 |
| `cf_reviewed_at` | mapped | `timestamp without time zone` | datetime | Reviewed at | 3 |


## Task (`tasks`, 10 rows)

| Column | Status | SQL type | Field type | Label | Populated |
|---|---|---|---|---|---|
| `cf_detached_context` | orphaned | `character varying` | — | — | 3 |
| `cf_effort_hours` | mapped | `double precision` | float | Effort hours | 3 |
| `cf_internal_notes` | mapped | `text` | text | Internal notes | 3 |
| `cf_task_category` | mapped | `character varying` | select | Task category | 3 |


## Fields with no field group

| Name | Type | Field type |
|---|---|---|
| `cf_detached_context` | CustomField | string |
