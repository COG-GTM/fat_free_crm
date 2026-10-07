# frozen_string_literal: true

require File.expand_path(File.dirname(__FILE__) + '/../../../spec_helper')
require 'json'
require 'open3'
require 'fat_free_crm/migration/column_census'
require Rails.root.join('script/migration/production_shaped_manifest')

describe FatFreeCRM::Migration::ColumnCensus do
  let(:manifest) { FatFreeCRM::Migration::ProductionShapedManifest }
  let(:census_path) { Rails.root.join('docs/migration/baseline/column-census.json') }
  let(:schema_path) { Rails.root.join('docs/migration/baseline/production-shaped-schema.sql') }

  def expect_manifest_entries(report)
    manifest::EXPECTED.each do |expected|
      table = report.fetch('tables').find { |entry| entry.fetch('klass') == expected.fetch(:klass) }
      actual = table.fetch('columns').find { |entry| entry.fetch('name') == expected.fetch(:name) }

      expect(actual).not_to be_nil, "missing #{expected.fetch(:klass)}.#{expected.fetch(:name)}"
      %i[status field_as type_mismatch yaml_serialized].each do |key|
        expect(actual[key.to_s]).to eq(expected[key]), "#{expected.fetch(:name)} #{key}"
      end
    end

    expected_names = manifest::EXPECTED.group_by { |entry| entry.fetch(:klass) }
    report.fetch('tables').each do |table|
      actual_names = table.fetch('columns').map { |entry| entry.fetch('name') }.sort
      expected = expected_names.fetch(table.fetch('klass')).map { |entry| entry.fetch(:name) }.sort
      expect(actual_names).to eq(expected), "unexpected custom columns for #{table.fetch('klass')}"
    end
    expect(report.fetch('unattached_fields').map { |entry| entry.fetch('name') }.sort)
      .to eq(manifest::EXPECTED_UNATTACHED.sort)
  end

  it 'matches the committed census and PostgreSQL schema artifacts to the manifest' do
    report = JSON.parse(File.read(census_path))
    schema = File.read(schema_path)
    expect_manifest_entries(report)
    expect(schema).to include('CREATE TABLE public.contacts')

    manifest::EXPECTED.reject { |entry| entry.fetch(:status) == 'missing_column' }.each do |expected|
      expect(schema).to include(expected.fetch(:name))
    end
    leads_table = schema[/CREATE TABLE public\.leads \(\n.*?^\);/m]
    expect(leads_table).not_to be_nil
    expect(leads_table).not_to include('cf_partner_code')
    expect(schema).not_to match(/^\\(?:un)?restrict /)
  end

  it 'matches the live rehearsal database census to the manifest', :production_shaped_db do
    database_url = ENV.fetch('PRODUCTION_SHAPED_DATABASE_URL', nil)
    skip 'set PRODUCTION_SHAPED_DATABASE_URL to run against the live rehearsal database' if database_url.blank?

    env = { 'RAILS_ENV' => 'development', 'DATABASE_URL' => database_url, 'COUNT_ROWS' => 'false' }
    stdout, stderr, status = Bundler.with_unbundled_env do
      Open3.capture3(env, 'bundle', 'exec', 'rake', 'ffcrm:migration:column_census', 'FORMAT=json')
    end
    expect(status).to be_success, stderr
    expect_manifest_entries(JSON.parse(stdout))
  end
end
