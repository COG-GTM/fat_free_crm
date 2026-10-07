# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../../../spec_helper')
require 'fat_free_crm/migration/column_census'

describe FatFreeCRM::Migration::ColumnCensus do
  # Builds a census whose connection reports the given cf_* columns, so the
  # classification logic can be exercised without live DDL.
  def census_for(columns, klass_names: ['Contact'], count_rows: false)
    connection = instance_double(ActiveRecord::ConnectionAdapters::AbstractAdapter,
                                 adapter_name: 'PostgreSQL')
    allow(connection).to receive_messages(table_exists?: true, columns: columns, select_value: 0)
    allow(connection).to receive(:quote_table_name) { |name| %("#{name}") }
    allow(connection).to receive(:quote_column_name) { |name| %("#{name}") }
    allow(connection).to receive(:select_all) do |sql|
      ActiveRecord::Base.connection.select_all(sql)
    end
    described_class.new(klass_names: klass_names, count_rows: count_rows, connection: connection)
  end

  def column(name, type: :string, sql_type: 'character varying')
    instance_double(ActiveRecord::ConnectionAdapters::Column,
                    name: name, type: type, sql_type: sql_type, null: true, default: nil)
  end

  def contact_field(name, as: 'string', label: name.titleize)
    field_group = create(:field_group, klass_name: 'Contact')
    create(:custom_field, name: name, as: as, label: label, field_group: field_group)
  end

  let(:columns_of) { ->(report, klass) { report[:tables].find { |t| t[:klass] == klass }[:columns] } }

  it "notes tables without custom columns in Markdown" do
    expect(census_for([]).to_markdown).to include('No `cf_*` columns.')
  end

  it "only reports columns using the cf_ prefix" do
    report = census_for([column('cf_hobby'), column('first_name'), column('cfg_not_custom')]).report

    expect(columns_of[report, 'Contact'].pluck(:name)).to eq(%w[cf_hobby])
  end

  it "marks a column with matching field metadata as mapped" do
    field = contact_field('cf_hobby')
    entry = columns_of[census_for([column('cf_hobby')]).report, 'Contact'].first

    expect(entry).to include(status: described_class::MAPPED,
                             field_id: field.id,
                             field_as: 'string',
                             expected_column_type: 'string',
                             type_mismatch: false,
                             yaml_serialized: false)
  end

  it "reads field metadata through the supplied connection" do
    contact_field('cf_default_only')
    connection = instance_double(ActiveRecord::ConnectionAdapters::AbstractAdapter,
                                 adapter_name: 'PostgreSQL')
    allow(connection).to receive_messages(
      table_exists?: true,
      columns: [column('cf_default_only'), column('cf_supplied_only')],
      select_value: 0
    )
    allow(connection).to receive(:quote_table_name) { |name| %("#{name}") }
    allow(connection).to receive(:quote_column_name) { |name| %("#{name}") }
    allow(connection).to receive(:select_all).and_return([
                                                           {
                                                             'id' => 987,
                                                             'type' => 'CustomField',
                                                             'name' => 'cf_supplied_only',
                                                             'label' => 'Supplied connection',
                                                             'as' => 'integer',
                                                             'group_id' => 123,
                                                             'klass_name' => 'Contact'
                                                           }
                                                         ])

    report = described_class.new(klass_names: %w[Contact], connection: connection).report
    entries = columns_of[report, 'Contact'].index_by { |entry| entry[:name] }

    expect(entries['cf_default_only']).to include(status: described_class::ORPHANED)
    expect(entries['cf_supplied_only']).to include(
      status: described_class::MAPPED, field_id: 987, field_label: 'Supplied connection',
      field_as: 'integer'
    )
  end

  it "marks a column with no field metadata as orphaned" do
    entry = columns_of[census_for([column('cf_deleted_field')]).report, 'Contact'].first

    expect(entry).to include(status: described_class::ORPHANED, field_id: nil)
  end

  it "reports field metadata whose column is absent from the database" do
    contact_field('cf_only_in_metadata')
    entries = columns_of[census_for([]).report, 'Contact']

    expect(entries.map { |e| e.values_at(:name, :status) })
      .to eq([['cf_only_in_metadata', described_class::MISSING_COLUMN]])
  end

  it "ignores field metadata belonging to another entity class" do
    contact_field('cf_hobby')
    report = census_for([column('cf_hobby')], klass_names: %w[Account]).report

    expect(columns_of[report, 'Account'].first).to include(status: described_class::ORPHANED)
  end

  it "flags a column whose type no longer matches its field type" do
    contact_field('cf_amount', as: 'integer')
    entry = columns_of[census_for([column('cf_amount')]).report, 'Contact'].first

    expect(entry).to include(type_mismatch: true, expected_column_type: 'integer',
                             active_record_type: 'string')
  end

  it "treats a datetime column as matching a timestamp field type" do
    contact_field('cf_signed_at', as: 'datetime')
    entry = columns_of[census_for([column('cf_signed_at', type: :datetime)]).report, 'Contact'].first

    expect(entry).to include(type_mismatch: false, expected_column_type: 'timestamp')
  end

  it "flags check_boxes columns as YAML serialized" do
    contact_field('cf_interests', as: 'check_boxes')
    entry = columns_of[census_for([column('cf_interests', type: :text, sql_type: 'text')]).report, 'Contact'].first

    expect(entry).to include(yaml_serialized: true, type_mismatch: false)
  end

  it "reports fields that belong to no field group" do
    field = contact_field('cf_homeless')
    Field.where(id: field.id).update_all(field_group_id: nil)
    report = census_for([]).report

    expect(report[:unattached_fields]).to contain_exactly(
      hash_including(name: 'cf_homeless', field_id: field.id,
                     status: described_class::UNATTACHED_FIELD)
    )
    expect(report[:summary][:unattached_fields]).to eq(1)
  end

  it "summarizes the census across tables" do
    contact_field('cf_hobby')
    contact_field('cf_interests', as: 'check_boxes')
    report = census_for([column('cf_hobby'), column('cf_orphan'), column('cf_interests')]).report

    expect(report[:summary]).to include(tables: 1, custom_columns: 3, mapped: 2, orphaned: 1,
                                        missing_columns: 0, yaml_serialized: 1)
    expect(report[:summary][:by_field_as]).to eq('string' => 1, 'check_boxes' => 1)
  end

  it "covers every model that declares has_fields by default" do
    expect(described_class::DEFAULT_KLASS_NAMES).to eq(%w[Account Campaign Contact Lead Opportunity Task])
    described_class::DEFAULT_KLASS_NAMES.each do |klass_name|
      expect(klass_name.constantize).to respond_to(:field_groups)
    end
  end

  describe "row counts" do
    it "counts populated rows and table totals when enabled" do
      connection = ActiveRecord::Base.connection
      census = described_class.new(klass_names: %w[Contact], connection: connection)
      allow(connection).to receive(:columns).with('contacts')
                                            .and_return([column('cf_hobby')])
      allow(connection).to receive(:select_value).and_call_original
      allow(connection).to receive(:select_value)
        .with('SELECT COUNT("cf_hobby") FROM "contacts"').and_return(3)

      table = census.report[:tables].first
      expect(table[:total_rows]).to eq(Contact.count)
      expect(table[:columns].first[:populated_rows]).to eq(3)
      expect(census.report[:row_counts_included]).to be true
    end

    it "omits row counts when disabled" do
      table = census_for([column('cf_hobby')]).report[:tables].first

      expect(table[:total_rows]).to be_nil
      expect(table[:columns].first[:populated_rows]).to be_nil
      expect(census_for([column('cf_hobby')]).report[:row_counts_included]).to be false
    end
  end

  describe "serialization" do
    subject(:census) { census_for([column('cf_hobby'), column('cf_orphan')]) }

    before { contact_field('cf_hobby') }

    it "renders parseable JSON" do
      parsed = JSON.parse(census.to_json)

      expect(parsed['adapter']).to eq('PostgreSQL')
      expect(parsed['tables'].first['columns'].pluck('status'))
        .to eq(%w[mapped orphaned])
    end

    it "renders Markdown with a summary and per-table sections" do
      markdown = census.to_markdown

      expect(markdown).to include('# Custom field (`cf_*`) column census')
      expect(markdown).to include('## Summary')
      expect(markdown).to include('## Contact (`contacts`')
      expect(markdown).to include('| `cf_hobby` | mapped |')
      expect(markdown).to include('| `cf_orphan` | orphaned |')
    end

    it "escapes pipes in field labels without adding Markdown cells" do
      contact_field('cf_region', label: 'Region | Area')
      markdown = census_for([column('cf_region')]).to_markdown
      row = markdown.lines.find { |line| line.include?('cf_region') }

      expect(row).to include('Region \\| Area')
      expect(row.split(/(?<!\\)\|/).length - 2).to eq(6)
    end

    it "escapes existing backslashes before pipes in field labels" do
      contact_field('cf_path', label: 'Path \| Segment')
      markdown = census_for([column('cf_path')]).to_markdown
      row = markdown.lines.find { |line| line.include?('cf_path') }

      expect(row).to include('| Path \\\\\| Segment |')
      expect(row.scan(/(?<!\\)(?:\\\\)*\|/).length).to eq(7)
    end
  end

  describe "a live custom field" do
    it "appears as a mapped column after CustomField creates its column" do
      field_group = create(:field_group, klass_name: 'Contact')
      create(:custom_field, label: 'Shoe size', as: 'integer', field_group: field_group)
      Contact.reset_column_information

      census = described_class.new(klass_names: %w[Contact])
      entry = census.report[:tables].first[:columns].find { |c| c[:name] == 'cf_shoe_size' }

      expect(entry).to include(status: described_class::MAPPED, field_as: 'integer',
                               type_mismatch: false)
    ensure
      ActiveRecord::Base.connection.remove_column(:contacts, :cf_shoe_size)
      Contact.reset_column_information
    end
  end

  describe "edge cases" do
    after { Contact.reset_column_information }

    def bare_connection
      instance_double(ActiveRecord::ConnectionAdapters::AbstractAdapter, adapter_name: 'PostgreSQL')
    end

    it "accepts a single class name" do
      census = described_class.new(klass_names: 'Contact', connection: bare_connection)

      expect(census.klass_names).to eq(%w[Contact])
    end

    it "reports no columns and no row count for a table that does not exist" do
      connection = bare_connection
      allow(connection).to receive_messages(table_exists?: false, select_value: nil)
      census = described_class.new(klass_names: %w[Contact], count_rows: true, connection: connection)

      expect(census.report[:tables].first).to include(table: 'contacts', total_rows: nil, columns: [])
      expect(census.report[:summary]).to include(tables: 1, custom_columns: 0)
    end

    it "treats every cf_ column as orphaned when the fields table is missing" do
      contact_field('cf_hobby')
      connection = bare_connection
      allow(connection).to receive(:table_exists?) { |table| table.to_s != Field.table_name }
      allow(connection).to receive_messages(columns: [column('cf_hobby')], select_value: nil)
      report = described_class.new(klass_names: %w[Contact], count_rows: false, connection: connection).report

      expect(columns_of[report, 'Contact'].first).to include(status: described_class::ORPHANED, field_id: nil)
      expect(report[:unattached_fields]).to be_empty
    end

    it "records a nil schema version when schema_migrations cannot be queried" do
      connection = bare_connection
      allow(connection).to receive(:table_exists?) { |table| table.to_s != Field.table_name }
      allow(connection).to receive(:columns).and_return([])
      allow(connection).to receive(:select_value).and_raise(ActiveRecord::StatementInvalid, 'no such table')
      census = described_class.new(klass_names: %w[Contact], count_rows: false, connection: connection)

      expect(census.report[:schema_version]).to be_nil
      expect(census.to_markdown).to include('Schema version: unknown')
    end

    it "does not flag a type mismatch for a field type with no registered column type" do
      field = contact_field('cf_ghost')
      Field.where(id: field.id).update_all(as: 'ghost')
      entry = columns_of[census_for([column('cf_ghost')]).report, 'Contact'].first

      expect(entry).to include(status: described_class::MAPPED, field_as: 'ghost',
                               expected_column_type: nil, type_mismatch: false)
    end

    it "records column nullability and defaults" do
      flag = instance_double(ActiveRecord::ConnectionAdapters::Column,
                             name: 'cf_flag', type: :boolean, sql_type: 'boolean', null: false, default: 'false')
      entry = columns_of[census_for([flag]).report, 'Contact'].first

      expect(entry).to include(null: false, default: 'false', sql_type: 'boolean', active_record_type: 'boolean')
    end

    it "sorts missing-column entries alongside physical columns by name" do
      contact_field('cf_a_missing')
      report = census_for([column('cf_c'), column('cf_b')]).report

      expect(columns_of[report, 'Contact'].pluck(:name)).to eq(%w[cf_a_missing cf_b cf_c])
    end

    it "tallies type mismatches and SQL types in the summary" do
      contact_field('cf_amount', as: 'integer')
      report = census_for([column('cf_amount'), column('cf_note', type: :text, sql_type: 'text')]).report

      expect(report[:summary]).to include(type_mismatches: 1, missing_columns: 0)
      expect(report[:summary][:by_sql_type]).to eq('character varying' => 1, 'text' => 1)
    end

    it "censuses several entity tables independently" do
      contact_field('cf_hobby')
      report = census_for([column('cf_hobby')], klass_names: %w[Contact Account]).report

      expect(report[:tables].map { |table| table.values_at(:klass, :table) })
        .to eq([%w[Contact contacts], %w[Account accounts]])
      expect(columns_of[report, 'Contact'].first[:status]).to eq(described_class::MAPPED)
      expect(columns_of[report, 'Account'].first[:status]).to eq(described_class::ORPHANED)
      expect(report[:summary]).to include(tables: 2, custom_columns: 2, mapped: 1, orphaned: 1)
    end

    it "uses the registered column type for paired custom fields" do
      field_group = create(:field_group, klass_name: 'Contact')
      CustomFieldDatePair.create!(field_group: field_group, label: 'Starts', name: 'cf_starts', as: 'date_pair')
      entry = columns_of[census_for([column('cf_starts', type: :date, sql_type: 'date')]).report, 'Contact'].first

      expect(entry).to include(status: described_class::MAPPED, field_type: 'CustomFieldDatePair',
                               field_as: 'date_pair', expected_column_type: 'date', type_mismatch: false)
    end

    it "stamps the report with an ISO 8601 generation time" do
      census = census_for([])

      expect { Time.iso8601(census.report[:generated_at]) }.not_to raise_error
    end
  end

  describe FatFreeCRM::Migration::ColumnCensus::MarkdownFormatter do
    subject(:markdown) { described_class.new(report).to_s }

    let(:report) do
      {
        generated_at: '2026-10-07T00:00:00Z',
        adapter: 'PostgreSQL',
        schema_version: nil,
        row_counts_included: false,
        tables: [
          {
            klass: 'Contact', table: 'contacts', total_rows: nil,
            columns: [
              { name: 'cf_interests', status: 'mapped', sql_type: 'text', field_as: 'check_boxes',
                field_label: 'Interests', populated_rows: nil, type_mismatch: false, yaml_serialized: true },
              { name: 'cf_score', status: 'mapped', sql_type: 'integer', field_as: 'float',
                field_label: 'Score', populated_rows: nil, type_mismatch: true, yaml_serialized: false },
              { name: 'cf_gone', status: 'missing_column', sql_type: nil, field_as: 'string',
                field_label: 'Gone', populated_rows: nil, type_mismatch: nil, yaml_serialized: false },
              { name: 'cf_orphan', status: 'orphaned', sql_type: 'character varying', field_as: nil,
                field_label: nil, populated_rows: nil, type_mismatch: nil, yaml_serialized: false }
            ]
          }
        ],
        unattached_fields: [{ name: 'cf_loose', status: 'unattached_field', field_type: 'CustomField', field_as: 'string' }],
        summary: { tables: 1, custom_columns: 3, mapped: 2, orphaned: 1, missing_columns: 1, unattached_fields: 1,
                   type_mismatches: 1, yaml_serialized: 1, by_field_as: { 'string' => 1 }, by_sql_type: { 'text' => 1 } }
      }
    end

    it "falls back to unknown for a missing schema version and ? for missing row counts" do
      expect(markdown).to include('- Schema version: unknown')
      expect(markdown).to include('## Contact (`contacts`, ? rows)')
    end

    it "annotates status cells with YAML and type mismatch flags" do
      expect(markdown).to include('| `cf_interests` | mapped (YAML) | `text` | check_boxes | Interests | — |')
      expect(markdown).to include('| `cf_score` | mapped (type mismatch) | `integer` | float | Score | — |')
    end

    it "renders placeholders for entries without a column or field metadata" do
      expect(markdown).to include('| `cf_gone` | missing_column | — | string | Gone | — |')
      expect(markdown).to include('| `cf_orphan` | orphaned | `character varying` | — | — | — |')
    end

    it "breaks the summary down by field type and SQL type" do
      expect(markdown).to include('| type_mismatches | 1 |')
      expect(markdown).to include('| field type `string` | 1 |')
      expect(markdown).to include('| sql type `text` | 1 |')
    end

    it "lists fields that belong to no field group" do
      expect(markdown).to include('## Fields with no field group')
      expect(markdown).to include('| `cf_loose` | CustomField | string |')
    end

    it "omits the unattached section when every field has a group" do
      report[:unattached_fields] = []

      expect(markdown).not_to include('## Fields with no field group')
    end
  end
end
