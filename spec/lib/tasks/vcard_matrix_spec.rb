# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "json"
require "rake"

RSpec.describe Rake::Task do
  let(:output_path) { Rails.root.join("tmp", "vcard-matrix-#{SecureRandom.hex(4)}.json") }
  let(:committed_path) { Rails.root.join("spring/src/test/resources/vcard/rails_vcard_matrix.json") }

  before do
    Rails.application.load_tasks unless Rake::Task.task_defined?("ffcrm:migration:vcard_matrix")
  end

  around do |example|
    previous_output = ENV.fetch("VCARD_OUTPUT", nil)
    ENV["VCARD_OUTPUT"] = output_path.to_s
    example.run
  ensure
    previous_output.nil? ? ENV.delete("VCARD_OUTPUT") : ENV["VCARD_OUTPUT"] = previous_output
  end

  after do
    FileUtils.rm_f(output_path)
    Rake::Task["ffcrm:migration:vcard_matrix"].reenable
  end

  it "records exact Rails vCard responses deterministically and leaves the database unchanged" do
    skip "requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    before_counts = table_counts
    generate_matrix
    first = File.read(output_path, encoding: "UTF-8")
    generate_matrix

    expect(File.read(output_path, encoding: "UTF-8")).to eq(first)
    matrix = JSON.parse(first)
    expect(matrix.fetch("cases").map { |entry| entry.fetch("name") })
      .to include("contact_all_fields_account_business_address", "contact_exact_75_codepoints",
                  "contact_lowest_id_business_address", "lead_without_company", "lead_long_multibyte")
    expect(matrix.fetch("cases")).to all(include("status" => 200, "content_type" => "text/x-vcard"))
    expect(matrix.fetch("corpus").fetch("contacts").size).to eq(9)
    expect(matrix.fetch("corpus").fetch("leads").size).to eq(5)
    expect(table_counts).to eq(before_counts)
  end

  it "keeps the committed Rails vCard matrix up to date" do
    skip "requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    generate_matrix
    expect(File.read(output_path, encoding: "UTF-8")).to eq(File.read(committed_path, encoding: "UTF-8"))
  end

  def generate_matrix
    Rake::Task["ffcrm:migration:vcard_matrix"].reenable
    Rake::Task["ffcrm:migration:vcard_matrix"].invoke
  end

  def table_counts
    %w[users accounts account_contacts contacts leads addresses].index_with do |table|
      ActiveRecord::Base.connection.select_value("SELECT COUNT(*) FROM #{table}").to_i
    end
  end
end
