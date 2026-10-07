# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "base64"
require "json"
require "rake"

RSpec.describe Rake::Task do
  let(:output_path) { Rails.root.join("tmp", "entity-fixture-#{SecureRandom.hex(4)}.sql") }
  let(:serialized_path) { Rails.root.join("tmp", "serialized-formats-#{SecureRandom.hex(4)}.json") }
  let(:tables) do
    %w[
      account_contacts account_opportunities accounts activities addresses avatars campaigns comments
      contact_opportunities contacts emails field_groups fields groups groups_users leads lists opportunities
      permissions preferences research_tools settings tags taggings tasks users versions
    ]
  end

  before do
    Rails.application.load_tasks unless Rake::Task.task_defined?("ffcrm:migration:entity_fixture")
  end

  around do |example|
    previous_output = ENV.fetch("OUTPUT", nil)
    previous_serialized = ENV.fetch("SERIALIZED_FORMATS_OUTPUT", nil)
    ENV["OUTPUT"] = output_path.to_s
    ENV["SERIALIZED_FORMATS_OUTPUT"] = serialized_path.to_s
    example.run
  ensure
    previous_output.nil? ? ENV.delete("OUTPUT") : ENV["OUTPUT"] = previous_output
    previous_serialized.nil? ? ENV.delete("SERIALIZED_FORMATS_OUTPUT") : ENV["SERIALIZED_FORMATS_OUTPUT"] = previous_serialized
  end

  after do
    FileUtils.rm_f(output_path)
    FileUtils.rm_f(serialized_path)
    Rake::Task["ffcrm:migration:entity_fixture"].reenable
  end

  it "writes all mapped tables, including soft-deleted and legacy subscriber rows, then rolls back" do
    before_counts = table_counts
    generate_fixture
    first_sql = File.read(output_path, encoding: "UTF-8")
    expect(first_sql).to include(
      "-- Regenerate with: FFCRM_ENTITY_FIXTURE=1 SECRET_KEY_BASE=entity-fixture-only-secret-key-base-0123456789abcdef bundle exec rake ffcrm:migration:entity_fixture"
    )
    tables.each do |table|
      table_name = ActiveRecord::Base.connection.quote_table_name(table)
      table_name = "public.#{table_name}" if ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
      expect(first_sql).to include("INSERT INTO #{table_name} ")
    end
    expect(first_sql).to include("--- []")
    expect(first_sql).to include("2025-01-02 03:04:05")
    expect(table_counts).to eq(before_counts)
  end

  it "produces identical SQL and serialized formats on consecutive runs" do
    generate_fixture
    first_sql = File.read(output_path, encoding: "UTF-8")
    first_serialized = File.read(serialized_path, encoding: "UTF-8")
    generate_fixture
    expect(File.read(output_path, encoding: "UTF-8")).to eq(first_sql)
    expect(File.read(serialized_path, encoding: "UTF-8")).to eq(first_serialized)
  end

  it "records values from Rails' subscribed-user and preference serializers" do
    generate_fixture

    golden = JSON.parse(File.read(serialized_path, encoding: "UTF-8"))
    type = Account.type_for_attribute("subscribed_users")
    golden.fetch("subscribed_users").each do |entry|
      expect(type.serialize(entry.fetch("value"))).to eq(entry.fetch("serialized"))
      expect(type.deserialize(entry.fetch("serialized"))).to eq(entry.fetch("value"))
    end
    golden.fetch("subscribed_users_legacy_reads").each do |entry|
      expect(type.deserialize(entry.fetch("serialized"))).to eq(entry.fetch("value"))
    end
    golden.fetch("preferences").each do |entry|
      expect(Base64.encode64(JSON.parse(entry.fetch("json")).to_json)).to eq(entry.fetch("serialized"))
      expect(JSON.parse(entry.fetch("json")).to_json).to eq(entry.fetch("json"))
      user = User.new(username: "preference-#{entry.fetch('name')}")
      user.save!(validate: false)
      user.pref[entry.fetch("name")] = JSON.parse(entry.fetch("json"))
      user.instance_variable_set(:@preference, nil)
      expect(user.pref[entry.fetch("name").to_sym]).to eq(JSON.parse(entry.fetch("json")))
    end
  end

  def generate_fixture
    Rake::Task["ffcrm:migration:entity_fixture"].reenable
    Rake::Task["ffcrm:migration:entity_fixture"].invoke
  end

  def table_counts
    tables.index_with do |table|
      ActiveRecord::Base.connection.select_value("SELECT COUNT(*) FROM #{table}").to_i
    end
  end
end
