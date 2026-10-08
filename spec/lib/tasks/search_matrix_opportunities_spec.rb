# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "json"
require "rake"

RSpec.describe Rake::Task do
  let(:output_path) { Rails.root.join("tmp", "opportunities-search-matrix-#{SecureRandom.hex(4)}.json") }
  let(:accounts_output_path) { Rails.root.join("tmp", "accounts-search-matrix-#{SecureRandom.hex(4)}.json") }
  let(:committed_path) { Rails.root.join("spring/src/test/resources/search/opportunities_search_matrix.json") }

  before do
    Rails.application.load_tasks unless Rake::Task.task_defined?("ffcrm:migration:search_matrix")
  end

  around do |example|
    previous_output = ENV.fetch("OUTPUT", nil)
    previous_opportunities_output = ENV.fetch("OPPORTUNITIES_OUTPUT", nil)
    ENV["OUTPUT"] = accounts_output_path.to_s
    ENV["OPPORTUNITIES_OUTPUT"] = output_path.to_s
    example.run
  ensure
    previous_output.nil? ? ENV.delete("OUTPUT") : ENV["OUTPUT"] = previous_output
    if previous_opportunities_output.nil?
      ENV.delete("OPPORTUNITIES_OUTPUT")
    else
      ENV["OPPORTUNITIES_OUTPUT"] = previous_opportunities_output
    end
  end

  after do
    FileUtils.rm_f(output_path)
    FileUtils.rm_f(accounts_output_path)
    Rake::Task["ffcrm:migration:search_matrix"].reenable
  end

  it "records opportunity cases, is deterministic, and leaves the database unchanged" do
    skip "requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    before_counts = table_counts
    generate_matrix
    first = File.read(output_path, encoding: "UTF-8")
    generate_matrix
    expect(File.read(output_path, encoding: "UTF-8")).to eq(first)
    matrix = JSON.parse(first)
    expect(matrix.fetch("cases").size).to eq(36)
    expect(matrix.fetch("corpus").fetch("opportunities").size).to eq(31)
    expect(matrix.fetch("shows").size).to eq(4)
    expect(table_counts).to eq(before_counts)
  end

  it "keeps the committed opportunities matrix JSON up to date" do
    skip "requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    generate_matrix
    expect(File.read(output_path, encoding: "UTF-8")).to eq(File.read(committed_path, encoding: "UTF-8"))
  end

  def generate_matrix
    Rake::Task["ffcrm:migration:search_matrix"].reenable
    Rake::Task["ffcrm:migration:search_matrix"].invoke
  end

  def table_counts
    %w[
      users accounts contacts campaigns opportunities account_opportunities contact_opportunities tags taggings
      settings preferences versions
    ].index_with do |table|
      ActiveRecord::Base.connection.select_value("SELECT COUNT(*) FROM #{table}").to_i
    end
  end
end
