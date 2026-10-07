# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "json"
require "rake"

RSpec.describe Rake::Task do
  let(:output_path) { Rails.root.join("tmp", "authz-matrix-#{SecureRandom.hex(4)}.json") }
  let(:sql_path) { Rails.root.join("tmp", "authz-fixture-#{SecureRandom.hex(4)}.sql") }
  let(:corpus_path) { Rails.root.join("tmp", "authz-corpus-#{SecureRandom.hex(4)}.json") }
  let(:corpus_sql_path) { Rails.root.join("tmp", "authz-corpus-#{SecureRandom.hex(4)}.sql") }
  let(:env_keys) { %w[OUTPUT SQL_OUTPUT CONTRACT_OUTPUT CONTRACT_SQL_OUTPUT] }
  let(:tables) { FatFreeCRM::Migration::AuthzMatrix::TABLES }

  before do
    skip "ffcrm:migration:authz_matrix requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
    Rails.application.load_tasks unless Rake::Task.task_defined?("ffcrm:migration:authz_matrix")
    require "fat_free_crm/migration/authz_matrix"
  end

  around do |example|
    previous = ENV.to_h.slice(*env_keys)
    env_keys.zip([output_path, sql_path, corpus_path, corpus_sql_path]).each { |key, path| ENV[key] = path.to_s }
    example.run
  ensure
    env_keys.each { |key| previous.key?(key) ? ENV[key] = previous[key] : ENV.delete(key) }
  end

  after do
    FileUtils.rm_f(output_path)
    FileUtils.rm_f(sql_path)
    FileUtils.rm_f(corpus_path)
    FileUtils.rm_f(corpus_sql_path)
    Rake::Task["ffcrm:migration:authz_matrix"].reenable if Rake::Task.task_defined?("ffcrm:migration:authz_matrix")
  end

  it "writes the matrix and seed for every table, then rolls back" do
    before_counts = table_counts
    generate
    sql = File.read(sql_path, encoding: "UTF-8")
    tables.each { |table| expect(sql).to include("INSERT INTO public.#{ActiveRecord::Base.connection.quote_table_name(table)} ") }
    expect(table_counts).to eq(before_counts)

    matrix = JSON.parse(File.read(output_path, encoding: "UTF-8"))
    expect(matrix.fetch("visible").keys).to eq(%w[Account Campaign Contact Lead Opportunity Task Comment Email User])
    expect(matrix.fetch("visible").values.flat_map(&:keys).uniq).to eq(FatFreeCRM::Migration::AuthzMatrix::ACTORS)
  end

  it "records the surprising CanCanCan semantics the Spring Specifications must reproduce" do
    generate
    matrix = JSON.parse(File.read(output_path, encoding: "UTF-8"))
    records = matrix.fetch("records").fetch("Account")
    account = matrix.fetch("visible").fetch("Account")
    expect(account.dig("shared_user", "ids")).to include(records.fetch("private_with_permission"))
    expect(account.dig("stale_member", "ids")).not_to include(records.fetch("shared_ghost_group"))
    expect(account.dig("unrelated", "ids")).not_to include(records.fetch("shared_no_permissions"))
    expect(account.dig("owner", "ids")).to include(records.fetch("deleted_assigned"))
    account.each_value { |cell| expect(cell.fetch("count")).to eq(cell.fetch("ids").size) }
  end

  it "records the AB-266 contract corpus visibility documented in spring/contract-diff.md, then rolls back" do
    before_counts = table_counts
    generate
    expect(table_counts).to eq(before_counts)
    expect(User.count).to eq(before_counts.fetch("users"))
    visible = JSON.parse(File.read(corpus_path, encoding: "UTF-8")).fetch("visible")
    ids = ->(type, user) { visible.fetch(type).fetch(user).fetch("ids") }
    expect(ids.call("Account", "alice")).to eq((101..105).to_a)
    expect(ids.call("Account", "bob")).to eq([101, 103, 104, 105, 106])
    expect(ids.call("Account", "admin")).to eq((101..107).to_a)
    expect(ids.call("Account", "carol")).to eq([101])
    expect(ids.call("Task", "bob")).to eq([602, 603])
    expect(ids.call("Task", "carol")).to eq([603])
    expect(File.read(corpus_sql_path)).to include("INSERT INTO public.\"permissions\" ")
  end

  it "produces identical output on consecutive runs" do
    generate
    paths = [output_path, sql_path, corpus_path, corpus_sql_path]
    first = paths.map { |path| File.read(path) }
    generate
    expect(paths.map { |path| File.read(path) }).to eq(first)
  end

  def generate
    Rake::Task["ffcrm:migration:authz_matrix"].reenable
    expect { Rake::Task["ffcrm:migration:authz_matrix"].invoke }.to output(/Wrote/).to_stdout
  end

  def table_counts
    tables.index_with do |table|
      ActiveRecord::Base.connection.select_value("SELECT COUNT(*) FROM #{ActiveRecord::Base.connection.quote_table_name(table)}").to_i
    end
  end
end
