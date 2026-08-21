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

  it "accepts the md format alias" do
    run('ffcrm:migration:column_census', 'OUTPUT' => output_path.to_s, 'FORMAT' => 'md',
                                         'COUNT_ROWS' => 'false')

    expect(File.read(output_path)).to include('# Custom field (`cf_*`) column census')
  end

  it "includes row counts by default" do
    run('ffcrm:migration:column_census', 'OUTPUT' => output_path.to_s, 'FORMAT' => 'json')

    expect(JSON.parse(File.read(output_path))['row_counts_included']).to be true
  end

  it "rejects an unknown format" do
    expect { run('ffcrm:migration:column_census', 'FORMAT' => 'yaml') }
      .to raise_error(SystemExit).and output(/Unknown FORMAT/).to_stderr
  end

  it "dumps the schema structure for Flyway baselining" do
    run('ffcrm:migration:baseline_dump', 'OUTPUT' => output_path.to_s)

    expect(File.read(output_path)).to include('CREATE TABLE')
  end
end
