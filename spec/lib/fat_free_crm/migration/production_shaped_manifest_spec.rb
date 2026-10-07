# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../../../spec_helper')
require 'json'
require 'fat_free_crm/migration/column_census'
require Rails.root.join('script/migration/production_shaped_manifest')

describe FatFreeCRM::Migration::ProductionShapedManifest do
  let(:census) { FatFreeCRM::Migration::ColumnCensus }
  let(:entries) { described_class::EXPECTED }
  let(:committed_census) { JSON.parse(Rails.root.join('docs/migration/baseline/column-census.json').read) }
  let(:committed_markdown) { Rails.root.join('docs/migration/baseline/column-census.md').read }

  it "covers every has_fields model the census scans and nothing else" do
    expect(entries.pluck(:klass).uniq).to match_array(census::DEFAULT_KLASS_NAMES)
  end

  it "only uses census status values" do
    expect(entries.pluck(:status).uniq)
      .to contain_exactly(census::MAPPED, census::ORPHANED, census::MISSING_COLUMN)
  end

  it "names every column with the cf_ prefix and keeps names unique per class" do
    names = entries.pluck(:name)
    expect(names).to all(match(census::CUSTOM_COLUMN_PATTERN))
    expect(entries.map { |entry| entry.values_at(:klass, :name) }.uniq.size).to eq(entries.size)
  end

  it "only references registered field types" do
    registered = Field.field_types.keys
    entries.each do |entry|
      expect(registered).to include(entry[:field_as]), "#{entry[:name]} field_as" unless entry[:field_as].nil?
      expect(registered).to include(entry[:seed_field_as]), "#{entry[:name]} seed_field_as" if entry.key?(:seed_field_as)
    end
  end

  it "derives column names the same way CustomField does from the seed labels" do
    entries.each do |entry|
      label = entry[:name].delete_prefix('cf_').tr('_', ' ').capitalize
      field = CustomField.new(label: label, as: entry[:seed_field_as] || entry[:field_as],
                              field_group: build(:field_group, klass_name: entry[:klass]))

      expect(field.send(:generate_column_name)).to eq(entry[:name])
    end
  end

  it "flags YAML serialization exactly for check_boxes fields" do
    entries.each do |entry|
      expect(entry[:yaml_serialized]).to eq(entry[:field_as] == 'check_boxes'), entry[:name]
    end
  end

  it "records field metadata and type mismatch state consistently with the census status" do
    entries.each do |entry|
      case entry[:status]
      when census::ORPHANED
        expect(entry).to include(field_as: nil, type_mismatch: nil), entry[:name]
        expect(entry[:seed_field_as]).to be_present, "#{entry[:name]} needs a seed_field_as to create the column"
      when census::MISSING_COLUMN
        expect(entry[:field_as]).to be_present, entry[:name]
        expect(entry[:type_mismatch]).to be_nil, entry[:name]
      when census::MAPPED
        expect(entry[:field_as]).to be_present, entry[:name]
        expect(entry[:type_mismatch]).to be_in([true, false]), entry[:name]
      end
    end
  end

  it "flags a type mismatch only where the seeded column type differs from the recorded field type" do
    entries.select { |entry| entry[:status] == census::MAPPED }.each do |entry|
      seeded = Field.field_types.dig(entry[:seed_field_as] || entry[:field_as], :type)
      recorded = Field.field_types.dig(entry[:field_as], :type)

      expect(entry[:type_mismatch]).to eq(seeded != recorded), entry[:name]
    end
  end

  it "lists unattached fields that are also census entries" do
    expect(entries.pluck(:name)).to include(*described_class::EXPECTED_UNATTACHED)
  end

  describe "committed baseline artifacts" do
    it "match the manifest summary counts" do
      summary = committed_census.fetch('summary')
      by_status = entries.group_by { |entry| entry[:status] }.transform_values(&:size)

      expect(summary).to include(
        'tables' => census::DEFAULT_KLASS_NAMES.size,
        'custom_columns' => entries.count { |entry| entry[:status] != census::MISSING_COLUMN },
        'mapped' => by_status.fetch(census::MAPPED),
        'orphaned' => by_status.fetch(census::ORPHANED),
        'missing_columns' => by_status.fetch(census::MISSING_COLUMN),
        'unattached_fields' => described_class::EXPECTED_UNATTACHED.size,
        'type_mismatches' => entries.count { |entry| entry[:type_mismatch] },
        'yaml_serialized' => entries.count { |entry| entry[:yaml_serialized] }
      )
      expect(summary.fetch('by_field_as'))
        .to eq(entries.filter_map { |entry| entry[:field_as] }.tally)
    end

    it "record the JSON census with row counts from a PostgreSQL database" do
      expect(committed_census).to include('adapter' => 'PostgreSQL', 'row_counts_included' => true)
      expect(committed_census.fetch('tables').pluck('klass')).to eq(census::DEFAULT_KLASS_NAMES)
    end

    it "render every manifest entry in the Markdown census with the matching status cell" do
      entries.each do |entry|
        flags = []
        flags << 'type mismatch' if entry[:type_mismatch]
        flags << 'YAML' if entry[:yaml_serialized]
        status = flags.empty? ? entry[:status] : "#{entry[:status]} (#{flags.join(', ')})"

        expect(committed_markdown).to include("| `#{entry[:name]}` | #{status} |"), entry[:name]
      end
      described_class::EXPECTED_UNATTACHED.each do |name|
        expect(committed_markdown).to match(/## Fields with no field group\n.*\| `#{name}` \|/m)
      end
    end
  end
end
