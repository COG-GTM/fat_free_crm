# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "json"
require "rake"

RSpec.describe Rake::Task do
  let(:task_name) { "ffcrm:migration:audit_goldens" }
  let(:output_dir) { Rails.root.join("tmp", "audit-goldens-#{SecureRandom.hex(4)}") }
  let(:committed_dir) { Rails.root.join("spring/src/test/resources/audit") }

  before do
    Rails.application.load_tasks unless Rake::Task.task_defined?(task_name)
  end

  around do |example|
    previous_output = ENV.fetch("AUDIT_GOLDENS_OUTPUT", nil)
    ENV["AUDIT_GOLDENS_OUTPUT"] = output_dir.to_s
    example.run
  ensure
    previous_output.nil? ? ENV.delete("AUDIT_GOLDENS_OUTPUT") : ENV["AUDIT_GOLDENS_OUTPUT"] = previous_output
  end

  after do
    FileUtils.rm_rf(output_dir)
    Rake::Task[task_name].reenable
  end

  it "regenerates the committed audit declarations and cases without changing the database" do
    skip "requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    before_counts = audit_table_counts
    generate_goldens
    expect(File.binread(output_dir.join("rails_audit_goldens.json")))
      .to eq(File.binread(committed_dir.join("rails_audit_goldens.json")))
    expect(audit_table_counts).to eq(before_counts)
  end

  it "covers every declared model and all PaperTrail operations" do
    skip "requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    generate_goldens
    manifest = JSON.parse(File.read(output_dir.join("rails_audit_goldens.json")))
    declarations = manifest.fetch("declarations").map { |entry| entry.fetch("class") }
    cases = manifest.fetch("cases")
    expect_models_and_operations(cases, declarations)
    expect_materialized_updates(cases, declarations)
    expect_fresh_update_order(cases)
    expect_tag_list_cases(cases)
    expect_timestamp_update(cases)
    expect_typed_values_and_whodunnit(cases)
    expect_scenarios(manifest)
  end

  def generate_goldens
    Rake::Task[task_name].reenable
    Rake::Task[task_name].invoke
  end

  def expect_models_and_operations(cases, declarations)
    expect(cases.map { |entry| entry.fetch("model") }.uniq).to match_array(declarations)
    expect(cases.map { |entry| entry.fetch("op") }.uniq)
      .to match_array(
        %w[
          create update update_materialized ignored_update touch destroy timestamp_update
          update_tag_list update_tag_list_and_name update_tag_list_existing destroy_tagged
        ]
      )
    expect(cases.size).to eq(138)
  end

  def expect_materialized_updates(cases, declarations)
    declarations.each do |model|
      materialized = cases.select do |entry|
        entry.fetch("model") == model && entry.fetch("op") == "update_materialized"
      end
      expect(materialized.size).to eq(1)
      expect(materialized.first.fetch("assigned_order")).to be_empty
    end
  end

  def expect_fresh_update_order(cases)
    fresh_updates = cases.select { |entry| entry.fetch("op") == "update" }
    expect(fresh_updates).to all(satisfy do |entry|
      entry.fetch("assigned_order").any? || entry.fetch("model") == "AccountContact"
    end)
    expect(fresh_updates.find { |entry| entry.fetch("model") == "AccountContact" }
      .fetch("assigned_order")).to be_empty
  end

  def expect_tag_list_cases(cases)
    expected_orders = {
      "Account/update_tag_list/69134/68999" => ["tag_list"],
      "Account/update_tag_list_and_name/69135/68999" => %w[tag_list name],
      "Account/update_tag_list_existing/69136/68999" => ["tag_list"],
      "Account/destroy_tagged/69137/68999" => [],
      "Contact/update_tag_list/69138/68999" => ["tag_list"]
    }
    tag_cases = cases.select { |entry| expected_orders.key?(entry.fetch("id")) }
    expect(tag_cases.map { |entry| entry.fetch("id") }).to match_array(expected_orders.keys)
    tag_cases.each do |entry|
      expect(entry.fetch("assigned_order")).to eq(expected_orders.fetch(entry.fetch("id")))
      expect(entry.fetch("versions").size).to eq(1)
    end
    update_tag_list = tag_cases.find { |entry| entry.fetch("id") == "Account/update_tag_list/69134/68999" }
    expect(update_tag_list.fetch("versions").first.fetch("object_changes")).to include(
      "tag_list:\n- []\n- - alpha\n  - beta\n"
    )
    existing_tags = tag_cases.find { |entry| entry.fetch("id") == "Account/update_tag_list_existing/69136/68999" }
    expect(existing_tags.fetch("versions").first.fetch("object_changes")).to include(
      "tag_list:\n- - alpha\n  - old\n- - beta\n  - gamma\n"
    )
    combined_update = tag_cases.find do |entry|
      entry.fetch("id") == "Account/update_tag_list_and_name/69135/68999"
    end
    expect(combined_update.fetch("versions").first.fetch("object_changes")).to include(
      "name:\n- Tagged Account 69135\n- Tagged Account\n"
    )
    tagged_destroy = tag_cases.find { |entry| entry.fetch("id") == "Account/destroy_tagged/69137/68999" }
    expect(tagged_destroy.fetch("versions").first.fetch("object"))
      .to include("!ruby/array:ActsAsTaggableOn::TagList")
    contact_update = tag_cases.find { |entry| entry.fetch("id") == "Contact/update_tag_list/69138/68999" }
    expect(contact_update.fetch("versions").first.fetch("object_changes")).to include(
      "tag_list:\n- []\n- - alpha\n  - beta\n"
    )
  end

  def expect_timestamp_update(cases)
    timestamp_update = cases.find { |entry| entry.fetch("op") == "timestamp_update" }
    expect(timestamp_update.fetch("assigned_order")).to eq(["updated_at"])
    expect(timestamp_update.fetch("versions").size).to eq(1)
  end

  def expect_typed_values_and_whodunnit(cases)
    expect(cases.pluck("whodunnit").uniq).to contain_exactly(nil, "68999")
    yaml = cases.flat_map { |entry| entry.fetch("versions") }
                .flat_map { |version| [version.fetch("object"), version.fetch("object_changes")] }
                .compact.join("\n")
    expect(yaml).to include("!ruby/object:BigDecimal 36:0.12345678901234567890123456789e20")
    expect(yaml).to include("!ruby/object:BigDecimal 9:-0.1e-8")
    expect(yaml).to include("!ruby/object:BigDecimal 9:0.0")
  end

  def expect_scenarios(manifest)
    expect(manifest.fetch("scenarios").map { |entry| entry.fetch("name") }).to match_array(
      %w[
        dropbox_create_and_attach dropbox_keyword_lead dropbox_attach_new_lead dropbox_attach_to_account
        comment_reply account_website_job wikidata_service
      ]
    )
    expect(manifest.fetch("scenarios").flat_map { |entry| entry.fetch("rows") }).not_to be_empty
  end

  def audit_table_counts
    %w[accounts campaigns contacts leads opportunities tasks users versions comments emails addresses
       account_contacts account_opportunities fields field_groups tags taggings]
      .index_with { |table| ActiveRecord::Base.connection.select_value("SELECT COUNT(*) FROM #{table}").to_i }
  end
end
