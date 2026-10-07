# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../../../spec_helper')
require 'fat_free_crm/migration/column_census'

describe FatFreeCRM::Migration::ColumnCensus do
  # A connection double that reports the given cf_* columns for every table
  # and, optionally, only knows about the listed tables.
  def fake_connection(columns: [], tables: nil, field_rows: nil, schema_version: '20260101000000')
    connection = instance_double(ActiveRecord::ConnectionAdapters::AbstractAdapter,
                                 adapter_name: 'PostgreSQL')
    allow(connection).to receive(:table_exists?) { |name| tables.nil? || tables.include?(name.to_s) }
    allow(connection).to receive(:quote_table_name) { |name| %("#{name}") }
    allow(connection).to receive(:quote_column_name) { |name| %("#{name}") }
    allow(connection).to receive_messages(columns: columns, select_value: schema_version)
    if field_rows
      allow(connection).to receive(:select_all).and_return(field_rows)
    else
      allow(connection).to receive(:select_all) { |sql| ActiveRecord::Base.connection.select_all(sql) }
    end
    connection
  end

  def census_for(connection, klass_names: %w[Contact], count_rows: false)
    described_class.new(klass_names: klass_names, count_rows: count_rows, connection: connection)
  end

  def column(name, type: :string, sql_type: 'character varying', null: true, default: nil)
    instance_double(ActiveRecord::ConnectionAdapters::Column,
                    name: name, type: type, sql_type: sql_type, null: null, default: default)
  end

  def contact_field(name, as: 'string', label: name.titleize)
    field_group = create(:field_group, klass_name: 'Contact')
    create(:custom_field, name: name, as: as, label: label, field_group: field_group)
  end

  def contact_columns(report)
    report[:tables].find { |table| table[:klass] == 'Contact' }[:columns]
  end

  describe "constructor" do
    it "defaults to every has_fields model with row counts enabled" do
      census = described_class.new

      expect(census.klass_names).to eq(described_class::DEFAULT_KLASS_NAMES)
      expect(census.count_rows?).to be true
    end

    it "normalizes a single class name or symbols to an Array of Strings" do
      expect(described_class.new(klass_names: 'Contact').klass_names).to eq(%w[Contact])
      expect(described_class.new(klass_names: %i[Contact Lead]).klass_names).to eq(%w[Contact Lead])
    end

    it "produces an empty census for no class names" do
      report = census_for(fake_connection, klass_names: []).report

      expect(report[:tables]).to eq([])
      expect(report[:summary]).to include(tables: 0, custom_columns: 0, mapped: 0, orphaned: 0,
                                          missing_columns: 0, type_mismatches: 0, yaml_serialized: 0,
                                          by_field_as: {}, by_sql_type: {})
    end

    it "memoizes the report" do
      census = census_for(fake_connection)

      expect(census.report).to equal(census.report)
    end
  end

  describe "missing tables" do
    it "reports no columns and no row count when the entity table does not exist" do
      connection = fake_connection(columns: [column('cf_ignored')],
                                   tables: %w[fields field_groups schema_migrations])
      table = census_for(connection, count_rows: true).report[:tables].first

      expect(table).to include(klass: 'Contact', table: 'contacts', total_rows: nil, columns: [])
      expect(connection).not_to have_received(:columns)
    end

    it "reports field metadata as missing columns when the entity table does not exist" do
      contact_field('cf_hobby')
      connection = fake_connection(tables: %w[fields field_groups schema_migrations])
      entries = contact_columns(census_for(connection).report)

      expect(entries.map { |entry| entry.values_at(:name, :status) })
        .to eq([['cf_hobby', described_class::MISSING_COLUMN]])
    end

    it "treats every cf_ column as orphaned when the fields table does not exist" do
      contact_field('cf_hobby')
      connection = fake_connection(columns: [column('cf_hobby')], tables: %w[contacts schema_migrations])
      report = census_for(connection).report

      expect(contact_columns(report).first).to include(status: described_class::ORPHANED, field_id: nil)
      expect(report[:unattached_fields]).to eq([])
      expect(report[:summary]).to include(mapped: 0, orphaned: 1, unattached_fields: 0)
      expect(connection).not_to have_received(:select_all)
    end
  end

  describe "schema version" do
    it "records the highest schema_migrations version" do
      expect(census_for(fake_connection(schema_version: '20260413041448')).report[:schema_version])
        .to eq('20260413041448')
    end

    it "records a nil schema version when schema_migrations cannot be queried" do
      connection = fake_connection
      allow(connection).to receive(:select_value).and_raise(ActiveRecord::StatementInvalid, 'no such table')
      census = census_for(connection)

      expect(census.report[:schema_version]).to be_nil
      expect(census.to_markdown).to include('- Schema version: unknown')
    end

    it "matches the live schema_migrations table" do
      live_version = ActiveRecord::Base.connection.select_value('SELECT MAX(version) FROM schema_migrations').to_s

      expect(described_class.new(klass_names: [], count_rows: false).report[:schema_version]).to eq(live_version)
    end
  end

  describe "field metadata" do
    it "does not flag a type mismatch for a field type that is no longer registered" do
      rows = [{ 'id' => 7, 'type' => 'CustomField', 'name' => 'cf_widget', 'label' => 'Widget',
                'as' => 'legacy_widget', 'group_id' => 1, 'klass_name' => 'Contact' }]
      report = census_for(fake_connection(columns: [column('cf_widget')], field_rows: rows)).report
      entry = contact_columns(report).first

      expect(entry).to include(status: described_class::MAPPED, field_as: 'legacy_widget',
                               expected_column_type: nil, type_mismatch: false, yaml_serialized: false)
      expect(report[:summary]).to include(type_mismatches: 0, by_field_as: { 'legacy_widget' => 1 })
    end

    it "ignores CoreField metadata rows" do
      field_group = create(:field_group, klass_name: 'Contact')
      create(:field, type: 'CoreField', name: 'cf_core_only', as: 'string', field_group: field_group)
      report = census_for(fake_connection(columns: [column('cf_core_only')])).report

      expect(contact_columns(report).first).to include(status: described_class::ORPHANED, field_id: nil)
      expect(report[:unattached_fields]).to eq([])
    end

    it "describes a missing column without physical column attributes" do
      field = contact_field('cf_ghost', as: 'check_boxes', label: 'Ghost')
      entry = contact_columns(census_for(fake_connection).report).first

      expect(entry).to eq(name: 'cf_ghost', status: described_class::MISSING_COLUMN,
                          field_id: field.id, field_type: 'CustomField', field_label: 'Ghost',
                          field_as: 'check_boxes', expected_column_type: 'text', yaml_serialized: true)
    end

    it "passes column nullability, default and SQL type through" do
      entry = contact_columns(
        census_for(fake_connection(columns: [column('cf_flag', type: :boolean, sql_type: 'boolean',
                                                                null: false, default: 'false')])).report
      ).first

      expect(entry).to include(sql_type: 'boolean', active_record_type: 'boolean', null: false, default: 'false')
    end
  end

  describe "summary" do
    it "excludes missing columns from custom_columns and SQL type tallies but counts them separately" do
      contact_field('cf_real')
      contact_field('cf_ghost')
      summary = census_for(fake_connection(columns: [column('cf_real')])).report[:summary]

      expect(summary).to include(custom_columns: 1, mapped: 1, missing_columns: 1,
                                 by_field_as: { 'string' => 2 },
                                 by_sql_type: { 'character varying' => 1 })
    end

    it "counts type mismatches" do
      contact_field('cf_amount', as: 'integer')
      contact_field('cf_hobby')
      summary = census_for(fake_connection(columns: [column('cf_amount'), column('cf_hobby')])).report[:summary]

      expect(summary).to include(type_mismatches: 1, mapped: 2)
    end
  end

  describe "Markdown" do
    it "lists both the type mismatch and YAML flags in the status cell" do
      contact_field('cf_interests', as: 'check_boxes')
      markdown = census_for(fake_connection(columns: [column('cf_interests')])).to_markdown

      expect(markdown).to include('| `cf_interests` | mapped (type mismatch, YAML) |')
    end

    it "renders placeholder cells for a missing column" do
      contact_field('cf_ghost', label: 'Ghost')
      markdown = census_for(fake_connection).to_markdown

      expect(markdown).to include('| `cf_ghost` | missing_column | — | string | Ghost | — |')
    end

    it "renders a placeholder for populated rows when counts are disabled" do
      contact_field('cf_hobby', label: 'Hobby')
      markdown = census_for(fake_connection(columns: [column('cf_hobby')])).to_markdown

      expect(markdown).to include('| `cf_hobby` | mapped | `character varying` | string | Hobby | — |')
    end

    it "lists fields with no field group in a dedicated section" do
      field = contact_field('cf_homeless')
      Field.where(id: field.id).update_all(field_group_id: nil)
      markdown = census_for(fake_connection).to_markdown

      expect(markdown).to include('## Fields with no field group')
      expect(markdown).to include('| `cf_homeless` | CustomField | string |')
    end

    it "omits the unattached section when every field has a group" do
      expect(census_for(fake_connection).to_markdown).not_to include('## Fields with no field group')
    end

    it "includes adapter, row-count and tally rows" do
      contact_field('cf_hobby')
      markdown = census_for(fake_connection(columns: [column('cf_hobby')])).to_markdown

      expect(markdown).to include('- Adapter: PostgreSQL')
      expect(markdown).to include('- Row counts included: false')
      expect(markdown).to include('| field type `string` | 1 |')
      expect(markdown).to include('| sql type `character varying` | 1 |')
      expect(markdown).to include('## Contact (`contacts`, ? rows)')
    end
  end

  describe "JSON" do
    it "accepts the generator state argument that JSON.generate passes to to_json" do
      census = census_for(fake_connection(columns: [column('cf_orphan')]))

      expect(census.to_json(JSON::State.new)).to eq(census.to_json)
      expect(JSON.parse(census.to_json)['tables'].first['columns'].first)
        .to include('name' => 'cf_orphan', 'status' => 'orphaned')
    end

    it "serializes disabled row counts as null and a parseable timestamp" do
      parsed = JSON.parse(census_for(fake_connection(columns: [column('cf_orphan')])).to_json)

      expect(parsed).to include('row_counts_included' => false, 'schema_version' => '20260101000000')
      expect(parsed['tables'].first).to include('total_rows' => nil)
      expect(parsed['tables'].first['columns'].first).to include('populated_rows' => nil)
      expect { Time.iso8601(parsed.fetch('generated_at')) }.not_to raise_error
    end
  end

  describe "against the live database" do
    it "counts populated rows for a physically present orphaned column" do
      connection = ActiveRecord::Base.connection
      connection.add_column(:contacts, :cf_spec_orphan, :string)
      Contact.reset_column_information
      contacts = create_list(:contact, 3)
      contacts.first(2).each { |contact| contact.update_column(:cf_spec_orphan, 'kept') }

      table = described_class.new(klass_names: %w[Contact]).report[:tables].first
      entry = table[:columns].find { |c| c[:name] == 'cf_spec_orphan' }

      expect(table[:total_rows]).to eq(3)
      expect(entry).to include(status: described_class::ORPHANED, populated_rows: 2, field_id: nil)
    ensure
      connection.remove_column(:contacts, :cf_spec_orphan) if connection.column_exists?(:contacts, :cf_spec_orphan)
      Contact.reset_column_information
    end

    it "reports a custom field whose column was never created as missing_column" do
      allow(CustomField.connection).to receive(:add_column)
      field_group = create(:field_group, klass_name: 'Contact')
      field = create(:custom_field, label: 'Never created', as: 'date', field_group: field_group)

      entry = described_class.new(klass_names: %w[Contact], count_rows: false)
                             .report[:tables].first[:columns].find { |c| c[:name] == 'cf_never_created' }

      expect(entry).to include(status: described_class::MISSING_COLUMN, field_id: field.id,
                               field_as: 'date', expected_column_type: 'date')
      expect(Contact.column_names).not_to include('cf_never_created')
    end
  end
end
