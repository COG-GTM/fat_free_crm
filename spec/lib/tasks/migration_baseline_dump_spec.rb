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

  def run(task, env = {})
    env.each { |key, value| ENV[key] = value }
    Rake::Task[task].reenable
    Rake::Task[task].invoke
  ensure
    env.each_key { |key| ENV.delete(key) }
  end

  after do
    Rake.application = nil
    FileUtils.rm_f(output_path)
  end

  describe "ffcrm:migration:column_census" do
    it "accepts the md alias for the markdown format" do
      run('ffcrm:migration:column_census', 'OUTPUT' => output_path.to_s, 'FORMAT' => 'md',
                                           'COUNT_ROWS' => 'false')

      expect(File.read(output_path)).to include('# Custom field (`cf_*`) column census')
    end

    it "includes row counts unless COUNT_ROWS is explicitly false" do
      run('ffcrm:migration:column_census', 'OUTPUT' => output_path.to_s, 'FORMAT' => 'json')

      expect(JSON.parse(File.read(output_path))['row_counts_included']).to be true
    end
  end

  describe "ffcrm:migration:baseline_dump" do
    let(:pg_config) do
      instance_double(ActiveRecord::DatabaseConfigurations::HashConfig,
                      adapter: 'postgresql',
                      database: 'crm_production',
                      configuration_hash: { host: 'db.example.com', port: 5432,
                                            username: 'crm', password: 'sekret' })
    end

    it "invokes pg_dump with connection settings on PostgreSQL" do
      allow(ActiveRecord::Base).to receive(:connection_db_config).and_return(pg_config)
      captured = nil
      allow_any_instance_of(Object).to receive(:system) { |_, *args| # rubocop:disable RSpec/AnyInstance
        captured = args
        true
      }

      expect { run('ffcrm:migration:baseline_dump', 'OUTPUT' => output_path.to_s) }
        .to output(/Wrote postgresql schema dump to #{Regexp.escape(output_path.to_s)}/).to_stdout

      env, *args = captured
      expect(env).to eq('PGPASSWORD' => 'sekret')
      expect(args).to eq(['pg_dump', '--schema-only', '--no-owner', '--no-privileges',
                          '--file', output_path.to_s,
                          '--host', 'db.example.com', '--port', '5432',
                          '--username', 'crm', 'crm_production'])
    end

    it "aborts when pg_dump fails" do
      allow(ActiveRecord::Base).to receive(:connection_db_config).and_return(pg_config)
      allow_any_instance_of(Object).to receive(:system).and_return(false) # rubocop:disable RSpec/AnyInstance

      expect { run('ffcrm:migration:baseline_dump', 'OUTPUT' => output_path.to_s) }
        .to raise_error(SystemExit).and output(/pg_dump failed/).to_stderr
    end

    it "defaults the output path to a timestamped file under tmp" do
      default_output = nil
      expect { run('ffcrm:migration:baseline_dump') }
        .to output(%r{Wrote sqlite3 schema dump to .*tmp/schema-baseline-\d{8}T\d{6}Z\.sql}).to_stdout
        .and(change { Rails.root.glob('tmp/schema-baseline-*.sql') })
      default_output = Rails.root.glob('tmp/schema-baseline-*.sql').max
      expect(File.read(default_output)).to include('CREATE TABLE')
    ensure
      FileUtils.rm_f(default_output) if default_output
    end
  end
end
