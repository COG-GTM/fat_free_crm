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

  describe "field metadata query" do
    def core_field(field_group, name)
      CoreField.create!(field_group: field_group, label: name.titleize, name: name, as: 'string')
    end

    it "excludes CoreField rows like Field.custom_fields does" do
      field_group = create(:field_group, klass_name: 'Contact')
      core = core_field(field_group, 'cf_core_attached')
      detached_core = core_field(nil, 'cf_core_detached')
      report = census_for([]).report

      expect(Field.custom_fields.where(id: [core.id, detached_core.id])).to be_empty
      expect(columns_of[report, 'Contact']).to be_empty
      expect(report[:unattached_fields]).to be_empty
    end

    it "excludes rows with no STI type like Field.custom_fields does" do
      field = contact_field('cf_untyped')
      Field.where(id: field.id).update_all(type: nil)
      report = census_for([column('cf_untyped')]).report

      expect(Field.custom_fields.where(id: field.id)).to be_empty
      expect(columns_of[report, 'Contact'].first).to include(status: described_class::ORPHANED, field_id: nil)
      expect(report[:unattached_fields]).to be_empty
    end

    it "treats a field whose field group row no longer exists as unattached" do
      field = contact_field('cf_dangling')
      Field.where(id: field.id).update_all(field_group_id: FieldGroup.maximum(:id).to_i + 1000)
      report = census_for([column('cf_dangling')]).report

      expect(columns_of[report, 'Contact'].first).to include(status: described_class::ORPHANED, field_id: nil)
      expect(report[:unattached_fields]).to contain_exactly(
        hash_including(name: 'cf_dangling', field_id: field.id, status: described_class::UNATTACHED_FIELD)
      )
    end

    it "does not map a physical column to an unattached field of the same name" do
      field = contact_field('cf_homeless')
      Field.where(id: field.id).update_all(field_group_id: nil)
      report = census_for([column('cf_homeless')]).report

      expect(columns_of[report, 'Contact'].first).to include(status: described_class::ORPHANED, field_id: nil)
      expect(report[:unattached_fields].pluck(:field_id)).to eq([field.id])
      expect(report[:summary]).to include(mapped: 0, orphaned: 1, unattached_fields: 1)
    end

    it "queries field metadata once for every entity table" do
      contact_field('cf_hobby')
      census = census_for([column('cf_hobby')], klass_names: %w[Contact Account Lead])
      census.report
      census.to_markdown
      census.to_json

      expect(census.connection).to have_received(:select_all).once
      expect(census.connection).to have_received(:select_all).with(/FROM "fields" f LEFT JOIN "field_groups" fg/)
    end

    it "quotes identifiers through the supplied connection" do
      census = census_for([])
      census.report

      expect(census.connection).to have_received(:quote_table_name).with('fields')
      expect(census.connection).to have_received(:quote_table_name).with('field_groups')
      expect(census.connection).to have_received(:quote_column_name).with('as').at_least(:once)
      expect(census.connection).to have_received(:select_all).with(/WHERE f."type" <> 'CoreField'/)
    end

    it "accepts metadata rows keyed by symbols" do
      connection = instance_double(ActiveRecord::ConnectionAdapters::AbstractAdapter, adapter_name: 'PostgreSQL')
      allow(connection).to receive_messages(table_exists?: true, columns: [column('cf_sym')], select_value: 0)
      allow(connection).to receive(:quote_table_name) { |name| %("#{name}") }
      allow(connection).to receive(:quote_column_name) { |name| %("#{name}") }
      allow(connection).to receive(:select_all).and_return([
                                                             { id: 5, type: 'CustomField', name: 'cf_sym', label: 'Sym',
                                                               as: 'string', group_id: 9, klass_name: 'Contact' },
                                                             { id: 6, type: 'CustomField', name: 'cf_loose', label: 'Loose',
                                                               as: 'text', group_id: nil, klass_name: nil }
                                                           ])
      report = described_class.new(klass_names: %w[Contact], count_rows: false, connection: connection).report

      expect(columns_of[report, 'Contact'].first).to include(status: described_class::MAPPED, field_id: 5,
                                                             field_label: 'Sym', field_as: 'string')
      expect(report[:unattached_fields]).to contain_exactly(
        hash_including(name: 'cf_loose', field_id: 6, field_type: 'CustomField', field_as: 'text')
      )
    end

    it "matches the rows Field.custom_fields.includes(:field_group) would return" do
      contact_group = create(:field_group, klass_name: 'Contact')
      account_group = create(:field_group, klass_name: 'Account')
      create(:custom_field, name: 'cf_contact_a', label: 'Contact A', as: 'string', field_group: contact_group)
      create(:custom_field, name: 'cf_account_b', label: 'Account B', as: 'string', field_group: account_group)
      core_field(contact_group, 'cf_core')
      loose = create(:custom_field, name: 'cf_loose', label: 'Loose', as: 'string', field_group: contact_group)
      Field.where(id: loose.id).update_all(field_group_id: nil)

      expected = Field.custom_fields.includes(:field_group).to_a
      expected_by_klass = expected.select(&:field_group).group_by { |f| f.field_group.klass_name }
      report = census_for([], klass_names: %w[Contact Account]).report

      expect(columns_of[report, 'Contact'].pluck(:name)).to match_array(expected_by_klass['Contact'].map(&:name))
      expect(columns_of[report, 'Account'].pluck(:name)).to match_array(expected_by_klass['Account'].map(&:name))
      expect(report[:unattached_fields].pluck(:name)).to match_array(expected.reject(&:field_group).map(&:name))
      expect(report[:unattached_fields].pluck(:name)).to eq(%w[cf_loose])
    end
  end

  describe "Markdown escaping" do
    def formatter_for(tables: [], unattached_fields: [], by_field_as: {}, by_sql_type: {})
      report = {
        generated_at: '2026-10-07T00:00:00Z', adapter: 'PostgreSQL', schema_version: '1',
        row_counts_included: false, tables: tables, unattached_fields: unattached_fields,
        summary: { tables: tables.size, custom_columns: 0, mapped: 0, orphaned: 0, missing_columns: 0,
                   unattached_fields: unattached_fields.size, type_mismatches: 0, yaml_serialized: 0,
                   by_field_as: by_field_as, by_sql_type: by_sql_type }
      }
      described_class::MarkdownFormatter.new(report)
    end

    def cells(row)
      row.scan(/(?<!\\)(?:\\\\)*\|/).length - 1
    end

    it "escapes pipes and backslashes in the summary tallies" do
      markdown = formatter_for(by_field_as: { 'odd|as' => 2 }, by_sql_type: { 'back\\slash' => 1 }).to_s

      expect(markdown).to include('| field type `odd\\|as` | 2 |')
      expect(markdown).to include('| sql type `back\\\\slash` | 1 |')
    end

    it "escapes every cell of the unattached fields table" do
      field = { name: 'cf_a|b', status: 'unattached_field', field_type: 'Custom|Field', field_as: 'str|ing' }
      row = formatter_for(unattached_fields: [field]).to_s.lines.find { |line| line.include?('cf_a') }

      expect(row).to eq("| `cf_a\\|b` | Custom\\|Field | str\\|ing |\n")
      expect(cells(row)).to eq(3)
    end

    it "escapes every cell of a column row" do
      column = { name: 'cf_x|y', status: 'mapped', sql_type: 'char|acter', field_as: 'sel|ect',
                 field_label: 'Pipe | Label', populated_rows: 4, type_mismatch: false, yaml_serialized: false }
      table = { klass: 'Contact', table: 'contacts', total_rows: 4, columns: [column] }
      row = formatter_for(tables: [table]).to_s.lines.find { |line| line.include?('cf_x') }

      expect(row).to eq("| `cf_x\\|y` | mapped | `char\\|acter` | sel\\|ect | Pipe \\| Label | 4 |\n")
      expect(cells(row)).to eq(6)
    end

    it "leaves placeholder cells untouched" do
      column = { name: 'cf_orphan', status: 'orphaned', sql_type: nil, field_as: nil, field_label: nil,
                 populated_rows: nil, type_mismatch: nil, yaml_serialized: false }
      table = { klass: 'Contact', table: 'contacts', total_rows: nil, columns: [column] }
      markdown = formatter_for(tables: [table]).to_s

      expect(markdown).to include('| `cf_orphan` | orphaned | — | — | — | — |')
    end
  end
end
