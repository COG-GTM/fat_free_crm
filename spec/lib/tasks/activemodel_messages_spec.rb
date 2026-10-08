# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "json"
require "rake"

RSpec.describe Rake::Task do
  let(:output_path) { Rails.root.join("tmp", "activemodel-messages-#{SecureRandom.hex(4)}.json") }

  before do
    Rails.application.load_tasks unless Rake::Task.task_defined?("ffcrm:migration:activemodel_messages")
  end

  around do |example|
    previous = ENV.fetch("OUTPUT", nil)
    ENV["OUTPUT"] = output_path.to_s
    example.run
  ensure
    previous ? ENV["OUTPUT"] = previous : ENV.delete("OUTPUT")
    FileUtils.rm_f(output_path)
    Rake::Task["ffcrm:migration:activemodel_messages"].reenable
  end

  it "writes a sorted catalog with the message, format and model error trees" do
    Rake::Task["ffcrm:migration:activemodel_messages"].invoke
    catalog = JSON.parse(File.read(output_path, encoding: "UTF-8"))
    expect(catalog.keys).to eq(catalog.keys.sort)
    # rubocop:disable Style/FormatStringToken -- literal en.yml format tokens, not sprintf
    expect(catalog.dig("errors", "format")).to eq("%{attribute} %{message}")
    # rubocop:enable Style/FormatStringToken
    expect(catalog.dig("errors", "messages", "blank")).to eq("can't be blank")
    expect(catalog.dig("activerecord", "errors", "models", "task", "attributes", "name", "missing_task_name"))
      .to eq("^Please specify task name.")
    expect(catalog.dig("activerecord", "attributes", "task", "name")).to eq(Task.human_attribute_name("name"))
    expect(catalog.dig("activerecord", "attributes", "list", "url")).to eq(List.human_attribute_name("url"))
    expect(catalog.dig("activerecord", "attributes").keys)
      .to eq(%w[account campaign comment contact email lead list opportunity task user])
  end

  it "produces deterministic output" do
    Rake::Task["ffcrm:migration:activemodel_messages"].invoke
    first = File.read(output_path, encoding: "UTF-8")
    Rake::Task["ffcrm:migration:activemodel_messages"].invoke
    expect(File.read(output_path, encoding: "UTF-8")).to eq(first)
    expect(first).to end_with("\n")
  end
end
