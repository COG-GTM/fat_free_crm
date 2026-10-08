# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "json"
require "rake"

RSpec.describe Version do
  let(:rails_manifest) do
    JSON.parse(Rails.root.join("spring/src/test/resources/audit/rails_audit_goldens.json").read)
  end
  let(:spring_manifest) do
    JSON.parse(Rails.root.join("spring/src/test/resources/audit/spring_audit_versions.json").read)
  end

  before do
    Rails.application.load_tasks unless Rake::Task.task_defined?("ffcrm:migration:audit_goldens")
    audit_goldens_custom_fields
  end

  it "lets Rails deserialize, reify, and read the changeset of every Spring row" do
    rails_cases = cases_by_unique_id(rails_manifest)
    spring_cases = cases_by_unique_id(spring_manifest)
    expect(spring_cases.keys).to match_array(rails_cases.keys)
    expect(spring_cases.keys).to include(
      "Account/update_tag_list/69134/68999",
      "Account/update_tag_list_and_name/69135/68999",
      "Account/update_tag_list_existing/69136/68999",
      "Account/destroy_tagged/69137/68999",
      "Contact/update_tag_list/69138/68999"
    )
    spring_cases.each do |case_id, spring_case|
      rails_case = rails_cases.fetch(case_id)
      expect_versions_match(spring_case, rails_case)
    end
  end

  def cases_by_unique_id(manifest)
    cases = manifest.fetch("cases")
    ids = cases.map { |entry| entry.fetch("id") }
    expect(ids.uniq).to eq(ids)
    cases.index_by { |entry| entry.fetch("id") }
  end

  def expect_versions_match(spring_case, rails_case)
    spring_rows = spring_case.fetch("versions")
    rails_rows = rails_case.fetch("versions")
    expect(spring_rows.size).to eq(rails_rows.size)
    spring_rows.zip(rails_rows).each { |spring_row, rails_row| expect_version_match(spring_row, rails_row) }
  end

  def expect_version_match(spring_row, rails_row)
    attributes = spring_row.slice(
      "item_type", "item_id", "event", "whodunnit", "related_type", "related_id", "object", "object_changes"
    )
    spring_version = Version.create!(attributes)
    rails_version = Version.new(rails_row)
    spring_object = spring_version.object.nil? ? nil : spring_version.object_deserialized
    rails_object = rails_version.object.nil? ? nil : rails_version.object_deserialized
    expect(spring_object).to eq(rails_object)
    expect(spring_version.reify&.attributes).to eq(rails_version.reify&.attributes)
    expect(spring_version.changeset).to eq(rails_version.changeset)
    expect(spring_version.reify).to be_nil if spring_row.fetch("event") == "create"
  end
end
