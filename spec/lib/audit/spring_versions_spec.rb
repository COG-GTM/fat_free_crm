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
    rails_cases = rails_manifest.fetch("cases").index_by do |entry|
      [entry.fetch("model"), entry.fetch("op"), entry["whodunnit"]]
    end

    spring_manifest.fetch("cases").each do |spring_case|
      rails_case = rails_cases.fetch([spring_case.fetch("model"), spring_case.fetch("op"),
                                      spring_case["whodunnit"]])
      expect(spring_case.fetch("versions").size).to eq(rails_case.fetch("versions").size)

      spring_case.fetch("versions").zip(rails_case.fetch("versions")).each do |spring_row, rails_row|
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
  end
end
