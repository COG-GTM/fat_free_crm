# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../../spec_helper')
require 'rake'
require 'json'
require 'fat_free_crm/migration/authz_matrix'
require 'fat_free_crm/migration/authz_contract_corpus'

# spec/lib/tasks/authz_matrix_spec.rb runs the real generators on PostgreSQL. This spec pins the rake
# task's file handling (paths, JSON formatting, SQL written verbatim) with stubbed generators, so it
# runs on every adapter.
describe "ffcrm:migration:authz_matrix rake task" do # rubocop:disable RSpec/DescribeClass
  let(:env_keys) { %w[OUTPUT SQL_OUTPUT CONTRACT_OUTPUT CONTRACT_SQL_OUTPUT] }
  let(:dir) { Rails.root.join('tmp', "authz-rake-#{SecureRandom.hex(4)}") }
  let(:matrix_result) { { matrix: { "visible" => { "Account" => { "owner" => { "ids" => [1], "count" => 1 } } } }, sql: "INSERT INTO public.\"accounts\" (\"id\") VALUES ('1');\n" } }
  let(:corpus_result) { { matrix: { "source" => "db/contract_fixtures.rb" }, sql: "-- corpus\n" } }

  # Run against a private Rake application so the suite-wide one (loaded on demand by other task
  # specs via Rails.application.load_tasks) is neither cleared nor replaced.
  around do |example|
    previous = Rake.application
    Rake.application = Rake::Application.new
    example.run
  ensure
    Rake.application = previous
  end

  before do
    Rake::Task.define_task(:environment)
    load Rails.root.join('lib/tasks/ffcrm/authz_matrix.rake')
    allow(FatFreeCRM::Migration::AuthzMatrix).to receive(:new)
      .and_return(instance_double(FatFreeCRM::Migration::AuthzMatrix, generate: matrix_result))
    allow(FatFreeCRM::Migration::AuthzContractCorpus).to receive(:new)
      .and_return(instance_double(FatFreeCRM::Migration::AuthzContractCorpus, generate: corpus_result))
  end

  after { FileUtils.rm_rf(dir) }

  def run(env)
    original_env = env_keys.index_with { |key| ENV.fetch(key, nil) }
    env_keys.each { |key| ENV.delete(key) }
    env.each { |key, value| ENV[key] = value }
    Rake::Task['ffcrm:migration:authz_matrix'].reenable
    Rake::Task['ffcrm:migration:authz_matrix'].invoke
  ensure
    original_env.each { |key, value| value.nil? ? ENV.delete(key) : ENV[key] = value }
  end

  it "writes pretty JSON with a trailing newline and the SQL verbatim to the OUTPUT paths, creating directories" do
    paths = {
      'OUTPUT' => dir.join('nested', 'matrix.json'),
      'SQL_OUTPUT' => dir.join('nested', 'fixture.sql'),
      'CONTRACT_OUTPUT' => dir.join('corpus', 'matrix.json'),
      'CONTRACT_SQL_OUTPUT' => dir.join('corpus', 'corpus.sql')
    }

    expect { run(paths.transform_values(&:to_s)) }
      .to output(a_string_including(*paths.values.map { |path| "Wrote #{path}" })).to_stdout

    expect(File.read(paths['OUTPUT'])).to eq("#{JSON.pretty_generate(matrix_result[:matrix])}\n")
    expect(JSON.parse(File.read(paths['OUTPUT']))).to eq(matrix_result[:matrix])
    expect(File.read(paths['SQL_OUTPUT'])).to eq(matrix_result[:sql])
    expect(File.read(paths['CONTRACT_OUTPUT'])).to eq("#{JSON.pretty_generate(corpus_result[:matrix])}\n")
    expect(File.read(paths['CONTRACT_SQL_OUTPUT'])).to eq(corpus_result[:sql])
  end

  it "defaults to the committed Spring test resources when no paths are given" do
    written = {}
    allow(FileUtils).to receive(:mkdir_p)
    allow(File).to receive(:write) { |path, content, **| written[path.to_s] = content }

    expect { run({}) }.to output(/Wrote/).to_stdout

    authz = Rails.root.join('spring/src/test/resources/authz')
    expect(written.keys).to contain_exactly(
      authz.join('authz_matrix.json').to_s, authz.join('authz_fixture.sql').to_s,
      authz.join('contract_corpus_matrix.json').to_s, authz.join('contract_corpus.sql').to_s
    )
    expect(written[authz.join('authz_fixture.sql').to_s]).to eq(matrix_result[:sql])
    expect(FileUtils).to have_received(:mkdir_p).with(authz).at_least(:once)
  end

  it "resolves relative OUTPUT paths against Rails.root" do
    relative = "tmp/authz-rake-#{SecureRandom.hex(4)}"
    begin
      run('OUTPUT' => "#{relative}/m.json", 'SQL_OUTPUT' => "#{relative}/f.sql",
          'CONTRACT_OUTPUT' => "#{relative}/c.json", 'CONTRACT_SQL_OUTPUT' => "#{relative}/c.sql")
      expect(File).to exist(Rails.root.join(relative, 'm.json'))
      expect(Rails.root.join(relative, 'c.sql').read).to eq(corpus_result[:sql])
    ensure
      FileUtils.rm_rf(Rails.root.join(relative))
    end
  end
end
