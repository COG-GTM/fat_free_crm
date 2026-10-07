# frozen_string_literal: true

require File.expand_path(File.dirname(__FILE__) + '/../../../spec_helper')
require 'fat_free_crm/migration/authz_matrix'
require 'fat_free_crm/migration/authz_contract_corpus'

# Guards of the matrix generator that must hold on any adapter. The PostgreSQL
# end-to-end behaviour is covered by spec/lib/tasks/authz_matrix_spec.rb.
describe FatFreeCRM::Migration::AuthzMatrix do
  def postgres_connection(counts: {})
    connection = instance_double(ActiveRecord::ConnectionAdapters::AbstractAdapter, adapter_name: 'PostgreSQL')
    allow(connection).to receive(:quote_table_name) { |name| %("#{name}") }
    allow(connection).to receive(:select_value) do |sql|
      table = sql[/FROM "(\w+)"/, 1]
      counts.fetch(table, 0)
    end
    connection
  end

  it 'seeds exactly the tables the Spring fixture loads, keyed by the eight matrix actors' do
    expect(described_class::TABLES).to eq(%w[users groups groups_users permissions accounts campaigns contacts leads
                                             opportunities tasks comments emails])
    expect(described_class::ACTORS).to eq(%w[owner assignee shared_user group_member completer stale_member unrelated
                                             admin])
    expect(described_class::REGENERATE).to include('rake ffcrm:migration:authz_matrix')
  end

  it 'refuses to run on anything but PostgreSQL before touching any table' do
    connection = instance_double(ActiveRecord::ConnectionAdapters::AbstractAdapter, adapter_name: 'SQLite')

    expect { described_class.new(connection).generate }.to raise_error(RuntimeError, /requires PostgreSQL/)
  end

  it 'refuses to seed a database that already holds rows in a matrix table' do
    connection = postgres_connection(counts: { 'permissions' => 3 })

    expect { described_class.new(connection).generate }
      .to raise_error(RuntimeError, 'permissions must be empty before authz matrix generation')
  end

  it 'checks every matrix table for emptiness, not just the first one' do
    connection = postgres_connection(counts: { 'emails' => 1 })

    expect { described_class.new(connection).generate }
      .to raise_error(RuntimeError, 'emails must be empty before authz matrix generation')
    described_class::TABLES.each do |table|
      expect(connection).to have_received(:select_value).with(%(SELECT COUNT(*) FROM "#{table}"))
    end
  end

  it 'does not seed, dump or alter sequences when the emptiness check fails' do
    connection = postgres_connection(counts: { 'users' => 1 })

    expect { described_class.new(connection).generate }.to raise_error(RuntimeError, /users must be empty/)
    expect(connection).not_to have_received(:quote_table_name).with('groups_users')
  end

  it 'uses the current ActiveRecord connection by default' do
    adapter = ActiveRecord::Base.connection.adapter_name
    skip 'default-connection guard only observable on non-PostgreSQL adapters' if adapter == 'PostgreSQL'

    expect { described_class.new.generate }.to raise_error(RuntimeError, /requires PostgreSQL/)
  end

  describe FatFreeCRM::Migration::AuthzContractCorpus do
    it 'records the five contract-corpus users and extends the matrix tables with the corpus sequences' do
      expect(described_class::USERS).to eq(%w[admin alice bob sam carol])
      expect(described_class::EXTRA_SEQUENCES).to include('settings', 'addresses', 'tags', 'taggings')
      expect(described_class).to be < FatFreeCRM::Migration::AuthzMatrix
    end

    it 'refuses to run while CONTRACT_FIXTURES_RESET is set so the corpus cannot wipe the database' do
      connection = instance_double(ActiveRecord::ConnectionAdapters::AbstractAdapter, adapter_name: 'PostgreSQL')
      previous = ENV.fetch('CONTRACT_FIXTURES_RESET', nil)
      ENV['CONTRACT_FIXTURES_RESET'] = '1'

      expect { described_class.new(connection).generate }.to raise_error(RuntimeError, /unset CONTRACT_FIXTURES_RESET/)
    ensure
      previous.nil? ? ENV.delete('CONTRACT_FIXTURES_RESET') : ENV['CONTRACT_FIXTURES_RESET'] = previous
    end

    it 'still clears the Setting cache when generation aborts' do
      connection = instance_double(ActiveRecord::ConnectionAdapters::AbstractAdapter, adapter_name: 'SQLite')
      allow(Setting).to receive(:clear_cache!).and_call_original

      expect { described_class.new(connection).generate }.to raise_error(RuntimeError, /requires PostgreSQL/)
      expect(Setting).to have_received(:clear_cache!)
    end
  end
end
