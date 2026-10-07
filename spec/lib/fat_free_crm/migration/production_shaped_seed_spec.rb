# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../../../spec_helper')
require 'fat_free_crm/migration/column_census'
require Rails.root.join('script/migration/production_shaped_manifest')

describe FatFreeCRM::Migration::ProductionShapedManifest do
  describe 'manifest' do
    let(:entries) { described_class::EXPECTED }
    let(:klass_names) { FatFreeCRM::Migration::ColumnCensus::DEFAULT_KLASS_NAMES }
    let(:column_type) { ->(as) { Field.field_types.dig(as, :type) } }

    it "covers exactly the entity classes the census scans by default" do
      expect(entries.pluck(:klass).uniq).to match_array(klass_names)
    end

    it "uses a unique column name per entity class" do
      expect(entries.map { |entry| entry.values_at(:klass, :name) }.uniq.size).to eq(entries.size)
    end

    it "seeds every field with a registered field type" do
      entries.each do |entry|
        seeded_as = entry[:seed_field_as] || entry[:field_as]
        expect(Field.field_types.keys).to include(seeded_as), "#{entry[:name]} seeds unknown field type #{seeded_as.inspect}"
      end
    end

    it "derives each column name from its seed label the way CustomField does" do
      entries.each do |entry|
        label = entry[:name].delete_prefix('cf_').tr('_', ' ').capitalize
        field = build(:custom_field, label: label, name: nil,
                                     field_group: build(:field_group, klass_name: entry[:klass]))

        expect(field.send(:generate_column_name)).to eq(entry[:name])
      end
    end

    it "flags YAML serialization exactly for check_boxes fields" do
      entries.each do |entry|
        expect(entry[:yaml_serialized]).to eq(entry[:field_as] == 'check_boxes'), entry[:name]
      end
    end

    it "marks a type mismatch only where the drifted field type maps to a different column type" do
      entries.select { |entry| entry[:status] == 'mapped' }.each do |entry|
        seeded_type = column_type[entry[:seed_field_as] || entry[:field_as]]
        expect(entry[:type_mismatch]).to eq(seeded_type != column_type[entry[:field_as]]), entry[:name]
      end
    end

    it "leaves type checks unset for orphaned and missing-column entries" do
      entries.reject { |entry| entry[:status] == 'mapped' }.each do |entry|
        expect(entry[:type_mismatch]).to be_nil, entry[:name]
      end
      entries.select { |entry| entry[:status] == 'orphaned' }.each do |entry|
        expect(entry[:field_as]).to be_nil, entry[:name]
      end
      entries.select { |entry| entry[:status] == 'missing_column' }.each do |entry|
        expect(entry[:field_as]).to be_present, entry[:name]
      end
    end

    it "lists unattached fields as orphaned columns" do
      described_class::EXPECTED_UNATTACHED.each do |name|
        expect(entries.find { |entry| entry[:name] == name }).to include(status: 'orphaned')
      end
    end
  end

  describe "script/migration/seed_production_shaped.rb" do
    let(:klass_names) { FatFreeCRM::Migration::ColumnCensus::DEFAULT_KLASS_NAMES }

    def remove_dynamic_columns
      connection = ActiveRecord::Base.connection
      klass_names.each do |klass_name|
        klass = klass_name.constantize
        connection.columns(klass.table_name).map(&:name).grep(/\Acf_/).each do |name|
          connection.remove_column(klass.table_name, name)
        end
        klass.reset_column_information
      end
    end

    before { remove_dynamic_columns }
    after { remove_dynamic_columns }

    it "seeds a database whose census matches the committed manifest" do
      expect { load Rails.root.join('script/migration/seed_production_shaped.rb') }
        .to output(/matches the committed manifest/).to_stdout

      report = FatFreeCRM::Migration::ColumnCensus.new.report
      expect(report[:summary]).to include(tables: 6, custom_columns: 20, mapped: 18, orphaned: 2,
                                          missing_columns: 1, unattached_fields: 1,
                                          type_mismatches: 1, yaml_serialized: 1)

      contacts = report[:tables].find { |table| table[:klass] == 'Contact' }
      expect(contacts[:total_rows]).to eq(10)
      expect(contacts[:columns].find { |column| column[:name] == 'cf_interests' })
        .to include(status: 'mapped', yaml_serialized: true, populated_rows: 3)

      expect(Contact.where.not(cf_interests: nil).pluck(:cf_interests)).to all(eq(%w[Email Events]))
      expect(Account.columns_hash.fetch('cf_annual_value')).to have_attributes(type: :decimal, precision: 15, scale: 2)
      expect(Account.where.not(cf_annual_value: nil).pluck(:cf_annual_value)).to all(eq(BigDecimal('12345.67')))
      expect(Lead.column_names).not_to include('cf_partner_code')
      expect(Field.find_by(name: 'cf_legacy_region')).to be_nil
      expect(Field.find_by(name: 'cf_detached_context').field_group).to be_nil
    end
  end
end
