# frozen_string_literal: true

require File.expand_path("../../../spec_helper", __dir__)
require "fat_free_crm/migration/settings_cf_coverage"
require "tmpdir"
require "yaml"

RSpec.describe FatFreeCRM::Migration::SettingsCfCoverage do
  let(:root) { Rails.root.to_s }
  let(:rules_path) { Rails.root.join("docs/migration/settings-cf-coverage.rules.yml") }
  let(:markdown_path) { Rails.root.join("docs/migration/settings-cf-coverage.md") }

  def coverage(markdown: markdown_path, rules: rules_path, scan_root: root)
    described_class.new(root: scan_root, rules_path: rules, markdown_path: markdown)
  end

  it "classifies every real call site (no unclassified rows)" do
    rows = coverage.scan
    expect(rows).not_to be_empty
    expect(rows.map(&:kind).uniq).to match_array(%w[setting cf])
    expect(rows.select { |row| row.status == "unclassified" }).to be_empty
  end

  it "passes CHECK semantics against the committed markdown" do
    result = coverage.check(coverage.scan)
    expect(result[:unclassified]).to be_empty
    expect(result[:added]).to be_empty
    expect(result[:removed]).to be_empty
  end

  it "flags a new unclassified call site as a check failure" do
    Dir.mktmpdir do |tmp|
      FileUtils.mkdir_p(File.join(tmp, "app"))
      File.write(File.join(tmp, "app/example.rb"), "Setting.new_key\n")
      rules = File.join(tmp, "rules.yml")
      File.write(rules, YAML.dump([]))
      markdown = File.join(tmp, "coverage.md")
      File.write(markdown, "")
      service = coverage(markdown: markdown, rules: rules, scan_root: tmp)
      rows = service.scan
      expect(rows.size).to eq(1)
      expect(rows.first.status).to eq("unclassified")
      result = service.check(rows)
      expect(result[:unclassified].size).to eq(1)
      expect(result[:added].size).to eq(1)
    end
  end

  it "records unroll argument keys in the member column" do
    row = coverage.scan.find { |entry| entry.file == "app/controllers/tasks_controller.rb" && entry.member.include?("task_bucket") }
    expect(row.member).to eq("unroll(:task_bucket)")
    expect(row.status).to eq("partial")
  end
end
