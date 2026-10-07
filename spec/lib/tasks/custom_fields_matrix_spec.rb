# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "json"
require "rake"

RSpec.describe Rake::Task do
  let(:output_path) { Rails.root.join("tmp", "custom-fields-matrix-#{SecureRandom.hex(4)}.json") }
  let(:committed_path) { Rails.root.join("spring/src/test/resources/customfields/rails_custom_fields_matrix.json") }

  before do
    Rails.application.load_tasks unless Rake::Task.task_defined?("ffcrm:migration:custom_fields_matrix")
  end

  around do |example|
    previous_output = ENV.fetch("OUTPUT", nil)
    ENV["OUTPUT"] = output_path.to_s
    example.run
  ensure
    previous_output.nil? ? ENV.delete("OUTPUT") : ENV["OUTPUT"] = previous_output
  end

  after do
    FileUtils.rm_f(output_path)
    Rake::Task["ffcrm:migration:custom_fields_matrix"].reenable
  end

  it "records custom-field behaviour deterministically and rolls back the fixture" do
    skip "requires PostgreSQL" unless postgres_with_custom_fields_trigger?

    before_state = database_state
    generate_matrix
    first = File.read(output_path, encoding: "UTF-8")
    generate_matrix
    expect(File.read(output_path, encoding: "UTF-8")).to eq(first)
    matrix = JSON.parse(first)
    expect(matrix.fetch("fields").map { |field| field.fetch("as") }.uniq)
      .to include(*Field.field_types.keys)
    expect(matrix.fetch("rows").first.fetch("custom_fields")).to be_a(Hash)
    expect(matrix.fetch("validation_errors").fetch("errors")).to be_a(Hash)
    expect(matrix.fetch("search").values).not_to be_empty
    expect(database_state).to eq(before_state)
  end

  it "keeps the committed Rails matrix current" do
    skip "requires PostgreSQL" unless postgres_with_custom_fields_trigger?
    generate_matrix
    expect(File.read(output_path, encoding: "UTF-8")).to eq(File.read(committed_path, encoding: "UTF-8"))
  end

  def generate_matrix
    Rake::Task["ffcrm:migration:custom_fields_matrix"].reenable
    Rake::Task["ffcrm:migration:custom_fields_matrix"].invoke
  end

  def postgres_with_custom_fields_trigger?
    connection = ActiveRecord::Base.connection
    return false unless connection.adapter_name == "PostgreSQL"

    %w[accounts campaigns contacts leads opportunities tasks].all? do |table|
      connection.column_exists?(table, :custom_fields) &&
        connection.select_value(
          "SELECT 1 FROM pg_trigger WHERE tgrelid = #{connection.quote(table)}::regclass " \
          "AND tgname = 'ffcrm_sync_custom_fields' AND NOT tgisinternal"
        ).present?
    end
  end

  def database_state
    connection = ActiveRecord::Base.connection
    {
      "counts" => %w[accounts fields field_groups].index_with do |table|
        connection.select_value("SELECT COUNT(*) FROM #{connection.quote_table_name(table)}").to_i
      end,
      "custom_columns" => %w[accounts campaigns contacts leads opportunities tasks].index_with do |table|
        connection.columns(table).map(&:name).grep(/\Acf_/).sort
      end
    }
  end
end
