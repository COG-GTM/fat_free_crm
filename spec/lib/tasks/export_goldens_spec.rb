# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "json"
require "rake"

RSpec.describe Rake::Task do
  let(:task_name) { "ffcrm:migration:export_goldens" }
  let(:output_dir) { Rails.root.join("tmp", "export-goldens-#{SecureRandom.hex(4)}") }
  let(:committed_dir) { Rails.root.join("spring/src/test/resources/exports") }

  before do
    Rails.application.load_tasks unless Rake::Task.task_defined?(task_name)
  end

  around do |example|
    previous_output = ENV.fetch("EXPORT_GOLDENS_OUTPUT", nil)
    ENV["EXPORT_GOLDENS_OUTPUT"] = output_dir.to_s
    example.run
  ensure
    previous_output.nil? ? ENV.delete("EXPORT_GOLDENS_OUTPUT") : ENV["EXPORT_GOLDENS_OUTPUT"] = previous_output
  end

  after do
    FileUtils.rm_rf(output_dir)
    Rake::Task[task_name].reenable
  end

  it "regenerates the committed goldens byte-for-byte and leaves the database unchanged" do
    skip "requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    before_counts = table_counts
    generate_goldens
    expect(golden_files(output_dir)).to eq(golden_files(committed_dir))
    golden_files(committed_dir).each do |file|
      expect(File.binread(output_dir.join(file))).to eq(File.binread(committed_dir.join(file))), file
    end
    expect(table_counts).to eq(before_counts)
  end

  it "records every export family in CSV and SpreadsheetML without secrets or custom_fields" do
    skip "requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    generate_goldens
    cases = JSON.parse(File.read(output_dir.join("rails_export_goldens.json"))).fetch("cases")
    families = cases.map { |entry| entry.fetch("rails_path")[%r{\A/(\w+)\.}, 1] }.uniq
    expect(families).to match_array(%w[accounts campaigns contacts leads opportunities tasks activities])
    csv = cases.find { |entry| entry["name"] == "accounts_admin_csv" }
    expect(csv).to include("content_type" => "text/csv", "content_disposition" => "attachment; filename=accounts.csv")
    xls = cases.find { |entry| entry["name"] == "accounts_admin_xls" }
    expect(xls.fetch("content_type")).to eq("application/vnd.msexcel; charset=utf-8")
    expect(xls["content_disposition"]).to be_nil
    manifest = File.read(output_dir.join("rails_export_goldens.json"))
    expect(manifest).to include("encrypted_password", "password_salt")
    bodies = (golden_files(output_dir) - ["rails_export_goldens.json"]).map { |file| File.binread(output_dir.join(file)) }
    expect(bodies).to all(satisfy { |body| !body.start_with?("\xEF\xBB\xBF".b) })
    expect(bodies.join).not_to match(/custom_fields|encrypted_password|password_salt|persistence_token|perishable_token/)
  end

  def generate_goldens
    Rake::Task[task_name].reenable
    Rake::Task[task_name].invoke
  end

  def golden_files(dir)
    Dir.children(dir).sort
  end

  def table_counts
    %w[accounts campaigns contacts leads opportunities tasks users versions taggings tags fields field_groups]
      .index_with { |table| ActiveRecord::Base.connection.select_value("SELECT COUNT(*) FROM #{table}").to_i }
  end
end
