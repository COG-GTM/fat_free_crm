# frozen_string_literal: true

require Rails.root.join('script/migration/production_shaped_manifest')
require 'fat_free_crm/migration/column_census'

module ProductionShapedSeed
  module_function

  def run
    admin = create_admin
    groups = create_field_groups
    fields = create_custom_fields(groups)
    records = create_records(admin)
    populate_custom_fields(records)
    apply_drift(fields)
    validate_manifest!
  end

  def create_admin
    User.create!(
      username: 'production_shape_admin',
      email: 'production-shape-admin@example.test',
      password: 'production-shape-password',
      password_confirmation: 'production-shape-password',
      admin: true,
      confirmed_at: Time.current
    )
  end

  def create_field_groups
    FatFreeCRM::Migration::ColumnCensus::DEFAULT_KLASS_NAMES.index_with do |klass_name|
      FieldGroup.find_by(klass_name: klass_name) ||
        FieldGroup.create!(klass_name: klass_name, label: "Production-shaped #{klass_name}")
    end
  end

  def create_custom_fields(groups)
    FatFreeCRM::Migration::ProductionShapedManifest::EXPECTED.each_with_object({}) do |entry, fields|
      field_as = entry[:seed_field_as] || entry[:field_as]

      collection = case field_as
                   when 'select' then %w[Online Partner Event]
                   when 'check_boxes' then ['Email', 'Events', 'Product updates']
                   else []
                   end
      field = CustomField.create!(
        field_group: groups.fetch(entry[:klass]),
        label: entry[:name].delete_prefix('cf_').tr('_', ' ').capitalize,
        as: field_as,
        collection: collection
      )
      raise "Expected #{entry[:name]}, created #{field.name}" unless field.name == entry[:name]

      fields[entry[:name]] = field
    end
  end

  def create_records(admin)
    campaigns = Array.new(10) do |index|
      Campaign.create!(
        user: admin,
        name: "Production-shaped campaign #{index + 1}",
        status: Setting.unroll(:campaign_status).first.last.to_s,
        starts_on: Date.current,
        ends_on: 30.days.from_now.to_date
      )
    end
    accounts = Array.new(10) do |index|
      Account.create!(user: admin, name: "Production-shaped account #{index + 1}")
    end
    leads = Array.new(10) do |index|
      Lead.create!(
        user: admin,
        campaign: campaigns[index],
        first_name: "Lead#{index + 1}",
        last_name: 'Example',
        status: Setting.unroll(:lead_status).first.last.to_s
      )
    end
    contacts = Array.new(10) do |index|
      Contact.create!(
        user: admin,
        lead: leads[index],
        first_name: "Contact#{index + 1}",
        last_name: 'Example'
      )
    end
    opportunities = Array.new(10) do |index|
      Opportunity.create!(
        user: admin,
        campaign: campaigns[index],
        name: "Production-shaped opportunity #{index + 1}",
        stage: Setting.unroll(:opportunity_stage).first.last.to_s,
        probability: 40,
        amount: 2500
      )
    end
    tasks = Array.new(10) do |index|
      Task.create!(
        user: admin,
        name: "Production-shaped task #{index + 1}",
        bucket: 'due_asap',
        category: 'follow_up'
      )
    end

    {
      'Account' => accounts,
      'Campaign' => campaigns,
      'Contact' => contacts,
      'Lead' => leads,
      'Opportunity' => opportunities,
      'Task' => tasks
    }
  end

  def populate_custom_fields(records)
    values = {
      'Account' => {
        'cf_account_segment' => 'Enterprise',
        'cf_account_email' => 'accounts@example.test',
        'cf_annual_value' => BigDecimal('12345.67'),
        'cf_account_code' => 'ACCT-1001'
      },
      'Campaign' => {
        'cf_campaign_notes' => 'Production-shaped campaign notes',
        'cf_campaign_channel' => 'Online',
        'cf_campaign_launch_date' => Date.current
      },
      'Contact' => {
        'cf_interests' => %w[Email Events],
        'cf_marketing_opt_in' => true,
        'cf_profile_url' => 'https://example.test/contact',
        'cf_legacy_region' => 'Northwest'
      },
      'Lead' => {
        'cf_partner_code' => 'PARTNER-42',
        'cf_estimated_users' => 250,
        'cf_follow_up_at' => Time.current
      },
      'Opportunity' => {
        'cf_forecast_amount' => BigDecimal('9876.54'),
        'cf_confidence_score' => 4,
        'cf_reviewed_at' => Time.current
      },
      'Task' => {
        'cf_effort_hours' => 1.5,
        'cf_task_category' => 'Partner',
        'cf_internal_notes' => 'Production-shaped task notes',
        'cf_detached_context' => 'Unattached metadata sample'
      }
    }

    values.each do |klass_name, fields|
      records.fetch(klass_name).first(3).each do |record|
        fields.each { |name, value| record[name] = value }
        record.save!
      end
    end
  end

  def apply_drift(fields)
    fields.fetch('cf_legacy_region').destroy!
    ActiveRecord::Base.connection.remove_column(:leads, :cf_partner_code)
    Lead.reset_column_information
    fields.fetch('cf_confidence_score').update_columns(as: 'float')
    fields.fetch('cf_detached_context').update_columns(field_group_id: nil)
  end

  def validate_manifest!
    manifest = FatFreeCRM::Migration::ProductionShapedManifest
    report = FatFreeCRM::Migration::ColumnCensus.new(count_rows: false).report

    manifest::EXPECTED.each do |expected|
      table = report.fetch(:tables).find { |entry| entry.fetch(:klass) == expected.fetch(:klass) }
      actual = table.fetch(:columns).find { |entry| entry.fetch(:name) == expected.fetch(:name) }
      raise "Missing census entry #{expected.fetch(:klass)}.#{expected.fetch(:name)}" unless actual

      %i[status field_as type_mismatch yaml_serialized].each do |key|
        raise "Unexpected #{key} for #{expected.fetch(:name)}: #{actual[key].inspect}" unless actual[key] == expected[key]
      end

      column_exists = ActiveRecord::Base.connection.column_exists?(table.fetch(:table), expected.fetch(:name))
      should_exist = expected.fetch(:status) != 'missing_column'
      raise "Unexpected physical column state for #{expected.fetch(:name)}" unless column_exists == should_exist
    end

    expected_names = manifest::EXPECTED.group_by { |entry| entry.fetch(:klass) }
    report.fetch(:tables).each do |table|
      expected = expected_names.fetch(table.fetch(:klass)).map { |entry| entry.fetch(:name) }.sort
      actual = table.fetch(:columns).map { |entry| entry.fetch(:name) }.sort
      raise "Unexpected census columns for #{table.fetch(:klass)}: #{actual.inspect}" unless actual == expected
    end

    unattached = report.fetch(:unattached_fields).map { |entry| entry.fetch(:name) }.sort
    raise "Unexpected unattached fields: #{unattached.inspect}" unless unattached == manifest::EXPECTED_UNATTACHED.sort
  end
end

ProductionShapedSeed.run
puts 'Production-shaped seed matches the committed manifest.'
