# frozen_string_literal: true

module FatFreeCRM
  module Migration
    module ProductionShapedManifest
      EXPECTED = [
        { klass: 'Account', name: 'cf_account_segment', status: 'mapped', field_as: 'select', type_mismatch: false, yaml_serialized: false }.freeze,
        { klass: 'Account', name: 'cf_account_email', status: 'mapped', field_as: 'email', type_mismatch: false, yaml_serialized: false }.freeze,
        { klass: 'Account', name: 'cf_annual_value', status: 'mapped', field_as: 'decimal', type_mismatch: false, yaml_serialized: false }.freeze,
        { klass: 'Account', name: 'cf_account_code', status: 'mapped', field_as: 'string', type_mismatch: false, yaml_serialized: false }.freeze,
        { klass: 'Campaign', name: 'cf_campaign_notes', status: 'mapped', field_as: 'text', type_mismatch: false, yaml_serialized: false }.freeze,
        { klass: 'Campaign', name: 'cf_campaign_channel', status: 'mapped', field_as: 'select', type_mismatch: false, yaml_serialized: false }.freeze,
        { klass: 'Campaign', name: 'cf_campaign_launch_date', status: 'mapped', field_as: 'date', type_mismatch: false, yaml_serialized: false }.freeze,
        { klass: 'Contact', name: 'cf_interests', status: 'mapped', field_as: 'check_boxes', type_mismatch: false, yaml_serialized: true }.freeze,
        { klass: 'Contact', name: 'cf_marketing_opt_in', status: 'mapped', field_as: 'boolean', type_mismatch: false, yaml_serialized: false }.freeze,
        { klass: 'Contact', name: 'cf_profile_url', status: 'mapped', field_as: 'url', type_mismatch: false, yaml_serialized: false }.freeze,
        { klass: 'Contact', name: 'cf_legacy_region', status: 'orphaned', field_as: nil, seed_field_as: 'string', type_mismatch: nil, yaml_serialized: false }.freeze,
        { klass: 'Lead', name: 'cf_partner_code', status: 'missing_column', field_as: 'string', type_mismatch: nil, yaml_serialized: false }.freeze,
        { klass: 'Lead', name: 'cf_estimated_users', status: 'mapped', field_as: 'integer', type_mismatch: false, yaml_serialized: false }.freeze,
        { klass: 'Lead', name: 'cf_follow_up_at', status: 'mapped', field_as: 'datetime', type_mismatch: false, yaml_serialized: false }.freeze,
        { klass: 'Opportunity', name: 'cf_forecast_amount', status: 'mapped', field_as: 'decimal', type_mismatch: false, yaml_serialized: false }.freeze,
        { klass: 'Opportunity', name: 'cf_confidence_score', status: 'mapped', field_as: 'float', seed_field_as: 'integer', type_mismatch: true, yaml_serialized: false }.freeze,
        { klass: 'Opportunity', name: 'cf_reviewed_at', status: 'mapped', field_as: 'datetime', type_mismatch: false, yaml_serialized: false }.freeze,
        { klass: 'Task', name: 'cf_effort_hours', status: 'mapped', field_as: 'float', type_mismatch: false, yaml_serialized: false }.freeze,
        { klass: 'Task', name: 'cf_task_category', status: 'mapped', field_as: 'select', type_mismatch: false, yaml_serialized: false }.freeze,
        { klass: 'Task', name: 'cf_internal_notes', status: 'mapped', field_as: 'text', type_mismatch: false, yaml_serialized: false }.freeze,
        { klass: 'Task', name: 'cf_detached_context', status: 'orphaned', field_as: nil, seed_field_as: 'string', type_mismatch: nil, yaml_serialized: false }.freeze
      ].freeze

      EXPECTED_UNATTACHED = %w[cf_detached_context].freeze
    end
  end
end
