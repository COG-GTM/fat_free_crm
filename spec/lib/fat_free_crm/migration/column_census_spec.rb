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
    described_class.new(klass_names: klass_names, count_rows: count_rows, connection: connection)
  end

  def column(name, type: :string, sql_type: 'character varying')
    instance_double(ActiveRecord::ConnectionAdapters::Column,
                    name: name, type: type, sql_type: sql_type, null: true, default: nil)
  end

  def contact_field(name, as: 'string')
    field_group = create(:field_group, klass_name: 'Contact')
    create(:custom_field, name: name, as: as, label: name.titleize, field_group: field_group)
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
end
