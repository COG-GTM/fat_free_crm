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
    expect(cases.map { |entry| entry.fetch("model") }.uniq).to match_array(declarations)
    expect(cases.map { |entry| entry.fetch("op") }.uniq)
      .to match_array(%w[create update ignored_update touch destroy])
    expect(cases.size).to eq(120)
    expect(cases.pluck("whodunnit").uniq).to contain_exactly(nil, "68999")
    yaml = cases.flat_map { |entry| entry.fetch("versions") }
                .flat_map { |version| [version.fetch("object"), version.fetch("object_changes")] }
                .compact.join("\n")
    expect(yaml).to include("!ruby/object:BigDecimal 36:0.12345678901234567890123456789e20")
    expect(yaml).to include("!ruby/object:BigDecimal 9:-0.1e-8")
    expect(yaml).to include("!ruby/object:BigDecimal 9:0.0")
    expect(manifest.fetch("scenarios").map { |entry| entry.fetch("name") }).to match_array(
      %w[
        dropbox_create_and_attach dropbox_keyword_lead dropbox_attach_new_lead dropbox_attach_to_account
        comment_reply account_website_job wikidata_service
      ]
    )
    expect(manifest.fetch("scenarios").flat_map { |entry| entry.fetch("rows") }).not_to be_empty
  end

  def generate_goldens
    Rake::Task[task_name].reenable
    Rake::Task[task_name].invoke
  end

  def audit_table_counts
    %w[accounts campaigns contacts leads opportunities tasks users versions comments emails addresses
       account_contacts account_opportunities fields field_groups]
      .index_with { |table| ActiveRecord::Base.connection.select_value("SELECT COUNT(*) FROM #{table}").to_i }
  end
end
