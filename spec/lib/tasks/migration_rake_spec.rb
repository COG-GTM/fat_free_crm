# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../../spec_helper')
require 'rake'
require 'fat_free_crm/migration/column_census'

describe "ffcrm:migration rake tasks" do # rubocop:disable RSpec/DescribeClass
  before do
    Rake::Task.clear
    Rake.application = Rake::Application.new
    Rake::Task.define_task(:environment)
    load Rails.root.join('lib/tasks/ffcrm/migration.rake')
  end

  let(:output_path) { Rails.root.join('tmp', "census-#{SecureRandom.hex(4)}") }

  def run(task, env = {})
    original_env = env.keys.index_with { |key| ENV.fetch(key, nil) }
    env.each { |key, value| ENV[key] = value }
    Rake::Task[task].reenable
    Rake::Task[task].invoke
  ensure
    original_env.each { |key, value| ENV[key] = value }
  end

  after do
    Rake.application = nil
    FileUtils.rm_f(output_path)
  end

  it "writes a Markdown census to OUTPUT" do
    expect { run('ffcrm:migration:column_census', 'OUTPUT' => output_path.to_s) }
      .to output(/Wrote markdown census/).to_stdout

    expect(File.read(output_path)).to include('# Custom field (`cf_*`) column census')
  end

  it "writes a JSON census to OUTPUT" do
    run('ffcrm:migration:column_census', 'OUTPUT' => output_path.to_s, 'FORMAT' => 'json',
                                         'COUNT_ROWS' => 'false')

    report = JSON.parse(File.read(output_path))
    expect(report['row_counts_included']).to be false
    expect(report['tables'].pluck('klass'))
      .to eq(FatFreeCRM::Migration::ColumnCensus::DEFAULT_KLASS_NAMES)
  end

  it "prints the census when no OUTPUT is given" do
    expect { run('ffcrm:migration:column_census', 'COUNT_ROWS' => 'false') }
      .to output(/## Summary/).to_stdout
  end

  it "rejects an unknown format" do
    expect { run('ffcrm:migration:column_census', 'FORMAT' => 'yaml') }
      .to raise_error(SystemExit).and output(/Unknown FORMAT/).to_stderr
  end

  it "runs PostgreSQL pg_dump with the configured connection arguments and password" do
    config = instance_double(
      ActiveRecord::DatabaseConfigurations::HashConfig,
      adapter: 'postgresql',
      configuration_hash: { host: 'db.internal', port: 5432, username: 'crm', password: 'secret' },
      database: 'ffcrm_test'
    )
    allow(ActiveRecord::Base).to receive(:connection_db_config).and_return(config)
    status = instance_double(Process::Status, success?: true, exitstatus: 0)
    arguments = [
      'docker', 'run', '--rm', 'postgres:16', 'pg_dump',
      '--schema-only', '--no-owner', '--no-privileges',
      '--host', 'db.internal', '--port', '5432', '--username', 'crm', 'ffcrm_test'
    ]
    expect(Open3).to receive(:capture2)
      .with({ 'PGPASSWORD' => 'secret' }, *arguments)
      .and_return(["CREATE TABLE contacts;\n", status])

    run('ffcrm:migration:baseline_dump', 'OUTPUT' => output_path.to_s,
                                         'PG_DUMP' => 'docker run --rm postgres:16 pg_dump')

    expect(File.read(output_path)).to eq("CREATE TABLE contacts;\n")
  end

  it "removes PostgreSQL restrict meta-commands from the captured dump" do
    config = instance_double(
      ActiveRecord::DatabaseConfigurations::HashConfig,
      adapter: 'postgresql',
      configuration_hash: {},
      database: 'ffcrm_test'
    )
    allow(ActiveRecord::Base).to receive(:connection_db_config).and_return(config)
    status = instance_double(Process::Status, success?: true, exitstatus: 0)
    dump = "\\restrict random-key\nCREATE TABLE contacts;\n\\unrestrict random-key\n\n"
    allow(Open3).to receive(:capture2).and_return([dump, status])

    run('ffcrm:migration:baseline_dump', 'OUTPUT' => output_path.to_s)

    expect(File.read(output_path)).to eq("CREATE TABLE contacts;\n")
  end

  it "aborts when PostgreSQL pg_dump exits unsuccessfully" do
    config = instance_double(
      ActiveRecord::DatabaseConfigurations::HashConfig,
      adapter: 'postgresql',
      configuration_hash: {},
      database: 'ffcrm_test'
    )
    allow(ActiveRecord::Base).to receive(:connection_db_config).and_return(config)
    status = instance_double(Process::Status, success?: false, exitstatus: 2)
    allow(Open3).to receive(:capture2).and_return(['', status])

    expect do
      run('ffcrm:migration:baseline_dump', 'OUTPUT' => output_path.to_s)
    end.to raise_error(SystemExit).and output(/pg_dump failed with exit status 2/).to_stderr
  end

  it "uses the Rails structure dump for non-PostgreSQL adapters" do
    config = instance_double(
      ActiveRecord::DatabaseConfigurations::HashConfig,
      adapter: 'sqlite3',
      configuration_hash: {},
      database: 'db/fat_free_crm_test.sqlite3'
    )
    allow(ActiveRecord::Base).to receive(:connection_db_config).and_return(config)
    expect(ActiveRecord::Tasks::DatabaseTasks).to receive(:structure_dump)
      .with(config, output_path.to_s) { File.write(output_path, 'CREATE TABLE contacts;') }

    run('ffcrm:migration:baseline_dump', 'OUTPUT' => output_path.to_s)

    expect(File.read(output_path)).to include('CREATE TABLE')
  end
end
