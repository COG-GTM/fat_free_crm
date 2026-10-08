# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "json"
require "rake"

RSpec.describe Rake::Task do
  let(:task_name) { "ffcrm:migration:settings_matrix" }
  let(:output_path) { Rails.root.join("tmp", "settings-matrix-#{SecureRandom.hex(4)}.json") }
  let(:committed_path) { Rails.root.join("spring/src/test/resources/settings/rails_settings_matrix.json") }

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

  it "records the four tier states deterministically and leaves the database and yaml_settings unchanged" do
    skip "requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    yaml_before = Setting.yaml_settings
    count_before = ActiveRecord::Base.connection.select_value("SELECT COUNT(*) FROM settings").to_i
    generate_matrix
    first = File.read(output_path, encoding: "UTF-8")
    generate_matrix
    expect(File.read(output_path, encoding: "UTF-8")).to eq(first)
    matrix = JSON.parse(first)
    expect(matrix.fetch("states").pluck("state")).to eq(
      %w[defaults_only with_override_file db_overlay_on_defaults db_overlay_on_override]
    )
    expect(ActiveRecord::Base.connection.select_value("SELECT COUNT(*) FROM settings").to_i).to eq(count_before)
    expect(Setting.yaml_settings).to eq(yaml_before)
  end

  it "records blank DB fall-through and wholesale replacement per Rails semantics" do
    skip "requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    generate_matrix
    states = JSON.parse(File.read(output_path, encoding: "UTF-8")).fetch("states").index_by { |state| state["state"] }
    overlay = states.fetch("db_overlay_on_defaults").fetch("values")
    expect(overlay.fetch("email_dropbox")).to eq("server" => "imap.db.test", "ssl" => true)
    expect(overlay.fetch("compound_address")).to be(true)
    expect(overlay.fetch("require_first_names")).to be(true)
    expect(overlay.fetch("db_only_integer")).to eq(0)
    expect(overlay.fetch("db_bad_class")).to eq("$error" => "Psych::DisallowedClass")
    override = states.fetch("db_overlay_on_override").fetch("values")
    expect(override.fetch("compound_address")).to be(false)
    expect(override.fetch("host")).to be_nil
    expect(override.fetch("override_only_key")).to eq(":symbol_value")
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
end
