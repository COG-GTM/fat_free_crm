# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "rake"

module LegacyFixtureTaskLoader
  extend Rake::DSL

  load Rails.root.join("lib/tasks/ffcrm/legacy_auth_fixture.rake")
end

class LegacyFixtureTaskEnvironmentDouble
  def initialize(local, name)
    @local = local
    @name = name
  end

  def local?
    @local
  end

  def to_s
    @name
  end
end

class LegacyFixtureTaskDatabaseConfigDouble
  attr_reader :database

  def initialize(database)
    @database = database
  end
end

class LegacyFixtureTaskConnectionDouble
  attr_reader :adapter_name

  def initialize(adapter_name)
    @adapter_name = adapter_name
  end
end

describe Rake::Task do
  let(:task) { Rake::Task["ffcrm:generate_legacy_auth_fixture"] }

  around do |example|
    confirmation = ENV.fetch("FFCRM_LEGACY_FIXTURE_CONFIRM", nil)
    output = ENV.fetch("FFCRM_LEGACY_FIXTURE_OUTPUT", nil)
    example.run
  ensure
    ENV["FFCRM_LEGACY_FIXTURE_CONFIRM"] = confirmation
    ENV["FFCRM_LEGACY_FIXTURE_OUTPUT"] = output
  end

  def local_environment
    allow(Rails).to receive(:env).and_return(
      instance_double(LegacyFixtureTaskEnvironmentDouble, local?: true, to_s: "test")
    )
  end

  def database_config(name = "fat_free_crm_test")
    instance_double(LegacyFixtureTaskDatabaseConfigDouble, database: name)
  end

  def postgres_connection
    instance_double(LegacyFixtureTaskConnectionDouble, adapter_name: "PostgreSQL")
  end

  it "refuses a non-local Rails environment" do
    allow(Rails).to receive(:env).and_return(
      instance_double(LegacyFixtureTaskEnvironmentDouble, local?: false, to_s: "production")
    )

    expect { task.execute }.to raise_error(SystemExit, /outside development or test/)
  end

  it "refuses a database name outside the development and test suffixes" do
    local_environment
    allow(ActiveRecord::Base).to receive(:connection_db_config).and_return(database_config("crm"))

    expect { task.execute }.to raise_error(SystemExit, /ending in _development or _test/)
  end

  it "requires explicit confirmation before writing the fixture" do
    local_environment
    allow(ActiveRecord::Base).to receive(:connection_db_config).and_return(database_config)

    expect { task.execute }.to raise_error(SystemExit, /FFCRM_LEGACY_FIXTURE_CONFIRM=1/)
  end

  it "refuses a non-PostgreSQL adapter" do
    local_environment
    ENV["FFCRM_LEGACY_FIXTURE_CONFIRM"] = "1"
    allow(ActiveRecord::Base).to receive_messages(
      connection_db_config: database_config,
      connection: instance_double(LegacyFixtureTaskConnectionDouble, adapter_name: "SQLite")
    )

    expect { task.execute }.to raise_error(SystemExit, /non-PostgreSQL/)
  end

  it "rolls back generated users after reading their persisted values" do
    local_environment
    ENV["FFCRM_LEGACY_FIXTURE_CONFIRM"] = "1"
    ENV["FFCRM_LEGACY_FIXTURE_OUTPUT"] = "tmp/legacy-auth-fixture-spec.json"
    allow(ActiveRecord::Base).to receive_messages(
      connection_db_config: database_config,
      connection: postgres_connection
    )

    rows = stub_user_persistence
    written_json = stub_fixture_write

    task.execute

    expect(rows).to be_empty
    expect(written_json.first).to include('"source": "persisted User rows created with User.create!"')
    expect(written_json.first).to include('"transaction": "rolled_back_after_reading_rows"')
  end

  def stub_user_persistence
    user_class = Struct.new(:id, :username, :email, :admin, :encrypted_password, :password_salt)
    rows = []
    allow(User).to receive(:create!) do |attributes|
      row = user_class.new(
        rows.length + 1,
        attributes[:username],
        attributes[:email],
        attributes[:admin],
        "encrypted-#{rows.length + 1}",
        "salt-#{rows.length + 1}"
      )
      rows << row
      row
    end
    allow(User).to receive(:find) { |id| rows.find { |row| row.id == id } }
    allow(User).to receive(:transaction) do |requires_new:, &block|
      expect(requires_new).to be(true)
      begin
        block.call
      rescue ActiveRecord::Rollback
        rows.clear
      end
    end
    rows
  end

  def stub_fixture_write
    root = instance_double(Pathname)
    fixture_path = instance_double(Pathname, dirname: instance_double(Pathname))
    allow(Rails).to receive(:root).and_return(root)
    allow(root).to receive(:join).and_return(fixture_path)
    allow(FileUtils).to receive(:mkdir_p)
    written_json = []
    allow(fixture_path).to receive(:write) { |content| written_json << content }
    written_json
  end
end
