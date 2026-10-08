# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "json"
require "rake"

RSpec.describe Rake::Task do
  let(:task_name) { "ffcrm:migration:task_buckets_matrix" }
  let(:output_path) { Rails.root.join("tmp", "task-buckets-matrix-#{SecureRandom.hex(4)}.json") }
  let(:committed_path) { Rails.root.join("spring/src/test/resources/search/tasks_search_matrix.json") }

  before do
    Rails.application.load_tasks unless Rake::Task.task_defined?(task_name)
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
    Rake::Task[task_name].reenable
  end

  it "records bucket cases on a frozen clock, is deterministic, and leaves the database unchanged" do
    skip "requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    before_counts = table_counts
    zone = Time.zone
    generate_matrix
    first = File.read(output_path, encoding: "UTF-8")
    generate_matrix
    expect(File.read(output_path, encoding: "UTF-8")).to eq(first)
    matrix = JSON.parse(first)
    expect(matrix.fetch("cases").size).to eq(27)
    expect(matrix.fetch("corpus").fetch("tasks").size).to eq(32)
    expect(table_counts).to eq(before_counts)
    expect(Time.zone).to eq(zone)
  end

  it "puts the boundary task in a different bucket per session time zone" do
    skip "requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    generate_matrix
    cases = JSON.parse(File.read(output_path, encoding: "UTF-8")).fetch("cases").index_by { |entry| entry["name"] }
    bucket_ids = ->(name, bucket) { cases.fetch(name).fetch("body").fetch(bucket).pluck("id") }
    expect(bucket_ids.call("alice_pending", "overdue")).to include(9603)
    expect(bucket_ids.call("alice_pending_utc_minus3", "due_today")).to include(9603)
    expect(cases.fetch("alice_pending").fetch("body").keys).to eq(
      %w[overdue due_asap due_today due_tomorrow due_this_week due_next_week due_later]
    )
  end

  it "keeps the committed matrix JSON up to date" do
    skip "requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    generate_matrix
    expect(File.read(output_path, encoding: "UTF-8")).to eq(File.read(committed_path, encoding: "UTF-8"))
  end

  def generate_matrix
    Rake::Task[task_name].reenable
    Rake::Task[task_name].invoke
  end

  def table_counts
    %w[users tasks settings versions].index_with do |table|
      ActiveRecord::Base.connection.select_value("SELECT COUNT(*) FROM #{table}").to_i
    end
  end
end
