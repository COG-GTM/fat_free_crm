# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "json"
require "rake"

RSpec.describe Rake::Task do
  let(:output_path) { Rails.root.join("tmp", "search-matrix-#{SecureRandom.hex(4)}.json") }
  let(:committed_path) { Rails.root.join("spring/src/test/resources/search/accounts_search_matrix.json") }
  let(:contacts_output_path) { Rails.root.join("tmp", "contacts-search-matrix-#{SecureRandom.hex(4)}.json") }
  let(:leads_output_path) { Rails.root.join("tmp", "leads-search-matrix-#{SecureRandom.hex(4)}.json") }
  let(:contacts_committed_path) { Rails.root.join("spring/src/test/resources/search/contacts_search_matrix.json") }
  let(:leads_committed_path) { Rails.root.join("spring/src/test/resources/search/leads_search_matrix.json") }

  before do
    Rails.application.load_tasks unless Rake::Task.task_defined?("ffcrm:migration:search_matrix")
  end

  around do |example|
    previous_output = ENV.fetch("OUTPUT", nil)
    previous_contacts_output = ENV.fetch("CONTACTS_OUTPUT", nil)
    previous_leads_output = ENV.fetch("LEADS_OUTPUT", nil)
    ENV["OUTPUT"] = output_path.to_s
    ENV["CONTACTS_OUTPUT"] = contacts_output_path.to_s
    ENV["LEADS_OUTPUT"] = leads_output_path.to_s
    example.run
  ensure
    previous_output.nil? ? ENV.delete("OUTPUT") : ENV["OUTPUT"] = previous_output
    previous_contacts_output.nil? ? ENV.delete("CONTACTS_OUTPUT") : ENV["CONTACTS_OUTPUT"] = previous_contacts_output
    previous_leads_output.nil? ? ENV.delete("LEADS_OUTPUT") : ENV["LEADS_OUTPUT"] = previous_leads_output
  end

  after do
    FileUtils.rm_f(output_path)
    FileUtils.rm_f(contacts_output_path)
    FileUtils.rm_f(leads_output_path)
    Rake::Task["ffcrm:migration:search_matrix"].reenable
  end

  it "records search cases, is deterministic, and leaves the database unchanged" do
    skip "requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    before_counts = table_counts
    generate_matrix
    first = File.read(output_path, encoding: "UTF-8")
    generate_matrix
    expect(File.read(output_path, encoding: "UTF-8")).to eq(first)
    matrix = JSON.parse(first)
    expect(matrix.fetch("cases").size).to eq(55)
    expect(matrix.fetch("corpus").fetch("accounts").size).to eq(30)
    expect(table_counts).to eq(before_counts)
  end

  it "keeps the committed matrix JSON up to date" do
    skip "requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    generate_matrix
    expect(File.read(output_path, encoding: "UTF-8")).to eq(File.read(committed_path, encoding: "UTF-8"))
  end

  it "records contacts and leads cases deterministically and leaves their corpora unchanged" do
    skip "requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    before_counts = table_counts
    generate_matrix
    contacts_first = File.read(contacts_output_path, encoding: "UTF-8")
    leads_first = File.read(leads_output_path, encoding: "UTF-8")
    generate_matrix

    contacts = JSON.parse(contacts_first)
    leads = JSON.parse(leads_first)
    expect(File.read(contacts_output_path, encoding: "UTF-8")).to eq(contacts_first)
    expect(File.read(leads_output_path, encoding: "UTF-8")).to eq(leads_first)
    expect(contacts.fetch("cases").map { |entry| entry.fetch("name") })
      .to include("default", "q_john_smith", "q_account_name", "preference_explicit_per_page")
    expect(leads.fetch("cases").map { |entry| entry.fetch("name") })
      .to include("default", "explicit_per_page_ignored", "filter_other", "filter_ignored_with_q")
    expect(leads.fetch("cases").first.fetch("facets")).to include("all", "other", "new")
    expect(contacts.fetch("corpus").fetch("contacts").size).to be >= 20
    expect(leads.fetch("corpus").fetch("leads").size).to be >= 20
    expect(table_counts).to eq(before_counts)
  end

  it "keeps the committed contacts and leads matrices up to date" do
    skip "requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    generate_matrix
    expect(File.read(contacts_output_path, encoding: "UTF-8"))
      .to eq(File.read(contacts_committed_path, encoding: "UTF-8"))
    expect(File.read(leads_output_path, encoding: "UTF-8"))
      .to eq(File.read(leads_committed_path, encoding: "UTF-8"))
  end

  def generate_matrix
    Rake::Task["ffcrm:migration:search_matrix"].reenable
    Rake::Task["ffcrm:migration:search_matrix"].invoke
  end

  def table_counts
    %w[users accounts contacts account_contacts opportunities contact_opportunities campaigns leads tags taggings
       preferences].index_with do |table|
      ActiveRecord::Base.connection.select_value("SELECT COUNT(*) FROM #{table}").to_i
    end
  end
end
