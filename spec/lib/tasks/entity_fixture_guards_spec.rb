# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path("../../spec_helper", __dir__)
require "rake"

# Guards around ffcrm:migration:entity_fixture: it must refuse to run on a populated database and
# must leave PaperTrail, time travel and the CustomField schema callback exactly as it found them.
RSpec.describe Rake::Task do
  let(:output_path) { Rails.root.join("tmp", "entity-fixture-guards-#{SecureRandom.hex(4)}.sql") }
  let(:serialized_path) { Rails.root.join("tmp", "serialized-formats-guards-#{SecureRandom.hex(4)}.json") }
  let(:task) { Rake::Task["ffcrm:migration:entity_fixture"] }

  before do
    Rails.application.load_tasks unless Rake::Task.task_defined?("ffcrm:migration:entity_fixture")
  end

  around do |example|
    original_output = ENV.fetch("OUTPUT", nil)
    original_serialized = ENV.fetch("SERIALIZED_FORMATS_OUTPUT", nil)
    ENV["OUTPUT"] = output_path.to_s
    ENV["SERIALIZED_FORMATS_OUTPUT"] = serialized_path.to_s
    example.run
  ensure
    ENV["OUTPUT"] = original_output
    ENV["SERIALIZED_FORMATS_OUTPUT"] = original_serialized
  end

  after do
    FileUtils.rm_f(output_path)
    FileUtils.rm_f(serialized_path)
    task.reenable
  end

  def add_column_callback_registered?
    CustomField._create_callbacks.any? { |callback| callback.kind == :before && callback.filter == :add_column }
  end

  it "refuses to run while a mapped table already has rows and writes nothing" do
    Setting.create!(name: "preexisting", value: "row")

    expect { task.invoke }.to raise_error(RuntimeError, "settings must be empty before fixture generation")

    expect(File).not_to exist(output_path)
    expect(File).not_to exist(serialized_path)
    expect(Setting.count).to eq(1)
    expect(Account.count).to eq(0)
  end

  it "refuses to run while a join table already has rows" do
    user = create(:user)
    group = Group.create!(name: "Populated")
    group.users << user

    expect { task.invoke }.to raise_error(RuntimeError, /\A(users|groups|groups_users) must be empty before fixture generation\z/)
    expect(File).not_to exist(output_path)
  end

  it "restores PaperTrail, the clock and the CustomField column callback after generating" do
    paper_trail_enabled = PaperTrail.enabled?
    expect(add_column_callback_registered?).to be(true)
    expect(PaperTrail.request.whodunnit).to be_nil

    task.invoke

    expect(File).to exist(output_path)
    expect(PaperTrail.enabled?).to eq(paper_trail_enabled)
    expect(PaperTrail.request.whodunnit).to be_nil
    expect(add_column_callback_registered?).to be(true)
    expect(Time.current).to be > Time.utc(2025, 1, 3)
    expect(CustomField.count).to eq(0)
    expect(Account.column_names).not_to include("cf_fixture_custom")
  end

  it "restores the CustomField column callback even when generation fails midway" do
    allow(PaperTrail).to receive(:enabled=).and_call_original
    allow(PaperTrail).to receive(:enabled=).with(true).and_raise("boom")

    expect { task.invoke }.to raise_error(RuntimeError, "boom")

    expect(add_column_callback_registered?).to be(true)
    expect(Account.count).to eq(0)
    expect(Setting.count).to eq(0)
    expect(File).not_to exist(output_path)
  end
end
