# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../../spec_helper')
require 'rake'
require 'fat_free_crm/migration/column_census'

describe "ffcrm:migration rake task edge cases" do # rubocop:disable RSpec/DescribeClass
  before do
    Rake::Task.clear
    Rake.application = Rake::Application.new
    Rake::Task.define_task(:environment)
    load Rails.root.join('lib/tasks/ffcrm/migration.rake')
  end

  let(:output_path) { Rails.root.join('tmp', "census-#{SecureRandom.hex(4)}") }
  let(:pg_status) { instance_double(Process::Status, success?: true, exitstatus: 0) }

  def run(task, env = {})
    original_env = env.keys.index_with { |key| ENV.fetch(key, nil) }
    env.each { |key, value| ENV[key] = value }
    Rake::Task[task].reenable
    Rake::Task[task].invoke
  ensure
    original_env.each { |key, value| ENV[key] = value }
  end

  def stub_pg_config(configuration_hash)
    config = instance_double(ActiveRecord::DatabaseConfigurations::HashConfig,
                             adapter: 'postgresql', configuration_hash: configuration_hash,
                             database: 'ffcrm_test')
    allow(ActiveRecord::Base).to receive(:connection_db_config).and_return(config)
  end

  after do
    Rake.application = nil
    FileUtils.rm_f(output_path)
  end

  describe "column_census" do
    it "accepts md as an alias for Markdown" do
      expect { run('ffcrm:migration:column_census', 'OUTPUT' => output_path.to_s, 'FORMAT' => 'md', 'COUNT_ROWS' => 'false') }
        .to output(/Wrote md census to #{Regexp.escape(output_path.to_s)}/).to_stdout

      expect(File.read(output_path)).to include('# Custom field (`cf_*`) column census')
    end

    it "matches FORMAT case-insensitively" do
      expect { run('ffcrm:migration:column_census', 'OUTPUT' => output_path.to_s, 'FORMAT' => 'JSON', 'COUNT_ROWS' => 'false') }.to output(/Wrote .*census/).to_stdout

      expect(JSON.parse(File.read(output_path))).to include('row_counts_included' => false)
    end

    it "includes row counts by default" do
      expect { run('ffcrm:migration:column_census', 'OUTPUT' => output_path.to_s, 'FORMAT' => 'json', 'COUNT_ROWS' => nil) }.to output(/Wrote .*census/).to_stdout

      report = JSON.parse(File.read(output_path))
      expect(report).to include('row_counts_included' => true)
      expect(report['tables'].pluck('total_rows')).to all(be_an(Integer))
    end

    it "only disables row counts for the literal string false" do
      expect { run('ffcrm:migration:column_census', 'OUTPUT' => output_path.to_s, 'FORMAT' => 'json', 'COUNT_ROWS' => 'no') }.to output(/Wrote .*census/).to_stdout

      expect(JSON.parse(File.read(output_path))).to include('row_counts_included' => true)
    end

    it "prints to stdout when OUTPUT is blank" do
      expect { run('ffcrm:migration:column_census', 'OUTPUT' => '', 'COUNT_ROWS' => 'false') }
        .to output(/## Summary/).to_stdout
    end

    it "does not write a file when the format is rejected" do
      expect { run('ffcrm:migration:column_census', 'OUTPUT' => output_path.to_s, 'FORMAT' => 'xml') }
        .to raise_error(SystemExit).and output(/Unknown FORMAT "xml"/).to_stderr

      expect(File).not_to exist(output_path)
    end
  end

  describe "baseline_dump" do
    it "writes to a timestamped tmp path when OUTPUT is not set" do
      stub_pg_config({})
      allow(Open3).to receive(:capture2).and_return(["CREATE TABLE contacts;\n", pg_status])
      existing = Dir.glob(Rails.root.join('tmp/schema-baseline-*.sql').to_s)

      expect { run('ffcrm:migration:baseline_dump', 'OUTPUT' => nil, 'PG_DUMP' => nil) }
        .to output(%r{Wrote postgresql schema dump to .*/tmp/schema-baseline-\d{8}T\d{6}Z\.sql}).to_stdout

      created = Dir.glob(Rails.root.join('tmp/schema-baseline-*.sql').to_s) - existing
      expect(created.size).to eq(1)
      expect(File.read(created.first)).to eq("CREATE TABLE contacts;\n")
    ensure
      FileUtils.rm_f(created || [])
    end

    it "runs the bare pg_dump executable without connection flags or PGPASSWORD when none are configured" do
      stub_pg_config({})
      expect(Open3).to receive(:capture2)
        .with({}, 'pg_dump', '--schema-only', '--no-owner', '--no-privileges', 'ffcrm_test')
        .and_return(["CREATE TABLE contacts;\n", pg_status])

      expect { run('ffcrm:migration:baseline_dump', 'OUTPUT' => output_path.to_s, 'PG_DUMP' => nil, 'PG_DUMP_HOST' => nil) }.to output(/Wrote .*schema dump/).to_stdout
    end

    it "prefers PG_DUMP_HOST over the configured host" do
      stub_pg_config(host: 'db.internal')
      expect(Open3).to receive(:capture2)
        .with({}, 'pg_dump', '--schema-only', '--no-owner', '--no-privileges', '--host', '127.0.0.1', 'ffcrm_test')
        .and_return(["CREATE TABLE contacts;\n", pg_status])

      expect { run('ffcrm:migration:baseline_dump', 'OUTPUT' => output_path.to_s, 'PG_DUMP' => nil, 'PG_DUMP_HOST' => '127.0.0.1') }.to output(/Wrote .*schema dump/).to_stdout
    end

    it "treats a blank PG_DUMP_HOST as unset" do
      stub_pg_config(host: 'db.internal')
      expect(Open3).to receive(:capture2)
        .with({}, 'pg_dump', '--schema-only', '--no-owner', '--no-privileges', '--host', 'db.internal', 'ffcrm_test')
        .and_return(["CREATE TABLE contacts;\n", pg_status])

      expect { run('ffcrm:migration:baseline_dump', 'OUTPUT' => output_path.to_s, 'PG_DUMP' => nil, 'PG_DUMP_HOST' => '') }.to output(/Wrote .*schema dump/).to_stdout
    end

    it "stringifies a numeric port and passes the password only through the environment" do
      stub_pg_config(port: 6543, password: 's3cret')
      expect(Open3).to receive(:capture2)
        .with({ 'PGPASSWORD' => 's3cret' }, 'pg_dump', '--schema-only', '--no-owner', '--no-privileges',
              '--port', '6543', 'ffcrm_test')
        .and_return(["CREATE TABLE contacts;\n", pg_status])

      expect { run('ffcrm:migration:baseline_dump', 'OUTPUT' => output_path.to_s, 'PG_DUMP' => nil, 'PG_DUMP_HOST' => nil) }.to output(/Wrote .*schema dump/).to_stdout
    end

    it "normalizes trailing whitespace to a single newline" do
      stub_pg_config({})
      allow(Open3).to receive(:capture2).and_return(["CREATE TABLE contacts;\n\n\n   \n", pg_status])

      expect { run('ffcrm:migration:baseline_dump', 'OUTPUT' => output_path.to_s, 'PG_DUMP' => nil) }.to output(/Wrote .*schema dump/).to_stdout

      expect(File.read(output_path)).to eq("CREATE TABLE contacts;\n")
    end

    it "only strips restrict meta-commands that start a line" do
      stub_pg_config({})
      dump = "\\restrict key\nCOMMENT ON TABLE contacts IS '\\restrict is not a command here';\n\\unrestrict key\n"
      allow(Open3).to receive(:capture2).and_return([dump, pg_status])

      expect { run('ffcrm:migration:baseline_dump', 'OUTPUT' => output_path.to_s, 'PG_DUMP' => nil) }.to output(/Wrote .*schema dump/).to_stdout

      expect(File.read(output_path))
        .to eq("COMMENT ON TABLE contacts IS '\\restrict is not a command here';\n")
    end

    it "does not write the output file when pg_dump fails" do
      stub_pg_config({})
      failed = instance_double(Process::Status, success?: false, exitstatus: 1)
      allow(Open3).to receive(:capture2).and_return(['partial', failed])

      expect { run('ffcrm:migration:baseline_dump', 'OUTPUT' => output_path.to_s, 'PG_DUMP' => nil) }
        .to raise_error(SystemExit).and output(/pg_dump failed with exit status 1/).to_stderr

      expect(File).not_to exist(output_path)
    end
  end
end
