# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../../../spec_helper')
require 'fat_free_crm/migration/column_census'

describe FatFreeCRM::Migration::ColumnCensus do
  def stub_connection(columns: [], table_exists: true, adapter_name: 'PostgreSQL')
    connection = instance_double(ActiveRecord::ConnectionAdapters::AbstractAdapter,
                                 adapter_name: adapter_name)
    allow(connection).to receive_messages(table_exists?: table_exists, columns: columns,
                                          select_value: 0)
    allow(connection).to receive(:quote_table_name) { |name| %("#{name}") }
    allow(connection).to receive(:quote_column_name) { |name| %("#{name}") }
    connection
  end

  def column(name, type: :string, sql_type: 'character varying')
    instance_double(ActiveRecord::ConnectionAdapters::Column,
                    name: name, type: type, sql_type: sql_type, null: true, default: nil)
  end

  def contact_field(name, as: 'string')
    field_group = create(:field_group, klass_name: 'Contact')
    create(:custom_field, name: name, as: as, label: name.titleize, field_group: field_group)
  end

  describe "missing tables" do
    it "reports no columns, no field rows, and a nil row count when tables are absent" do
      contact_field('cf_hobby')
      connection = stub_connection(table_exists: false)
      census = described_class.new(klass_names: %w[Contact], connection: connection)

      table = census.report[:tables].first
      expect(table[:columns]).to be_empty
      expect(table[:total_rows]).to be_nil
      expect(census.report[:summary]).to include(custom_columns: 0, missing_columns: 0)
    end
  end

  describe "schema version" do
    it "reports nil when the schema_migrations table cannot be queried" do
      connection = stub_connection
      allow(connection).to receive(:select_value)
        .with('SELECT MAX(version) FROM schema_migrations')
        .and_raise(ActiveRecord::StatementInvalid.new('no such table'))

      census = described_class.new(klass_names: %w[Contact], count_rows: false,
                                   connection: connection)
      expect(census.report[:schema_version]).to be_nil
      expect(census.to_markdown).to include('Schema version: unknown')
    end
  end

  describe "summary tallies" do
    it "counts type mismatches and tallies sql types across entries" do
      contact_field('cf_amount', as: 'integer')
      connection = stub_connection(columns: [column('cf_amount'),
                                             column('cf_note', type: :text, sql_type: 'text')])
      census = described_class.new(klass_names: %w[Contact], count_rows: false,
                                   connection: connection)

      summary = census.report[:summary]
      expect(summary[:type_mismatches]).to eq(1)
      expect(summary[:by_sql_type]).to eq('character varying' => 1, 'text' => 1)
    end

    it "counts a metadata-only field as a missing column, not a custom column" do
      contact_field('cf_only_in_metadata')
      connection = stub_connection(columns: [])
      census = described_class.new(klass_names: %w[Contact], count_rows: false,
                                   connection: connection)

      expect(census.report[:summary]).to include(custom_columns: 0, missing_columns: 1)
    end
  end

  describe "Markdown status flags" do
    it "annotates type mismatches and YAML serialization in the status cell" do
      contact_field('cf_amount', as: 'integer')
      contact_field('cf_interests', as: 'check_boxes')
      connection = stub_connection(columns: [column('cf_amount'),
                                             column('cf_interests', type: :text, sql_type: 'text')])
      census = described_class.new(klass_names: %w[Contact], count_rows: false,
                                   connection: connection)

      markdown = census.to_markdown
      expect(markdown).to include('mapped (type mismatch)')
      expect(markdown).to include('mapped (YAML)')
    end

    it "lists fields with no field group in their own section" do
      field = contact_field('cf_homeless')
      Field.where(id: field.id).update_all(field_group_id: nil)
      connection = stub_connection(columns: [])
      census = described_class.new(klass_names: %w[Contact], count_rows: false,
                                   connection: connection)

      markdown = census.to_markdown
      expect(markdown).to include('## Fields with no field group')
      expect(markdown).to include('`cf_homeless`')
    end
  end

  describe "report memoization" do
    it "builds the report once and reuses it across serializations" do
      connection = stub_connection(columns: [column('cf_hobby')])
      census = described_class.new(klass_names: %w[Contact], count_rows: false,
                                   connection: connection)

      census.to_json
      census.to_markdown
      expect(connection).to have_received(:columns).once
    end
  end
end
