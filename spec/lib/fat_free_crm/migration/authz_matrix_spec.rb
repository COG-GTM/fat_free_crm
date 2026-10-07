# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../../../spec_helper')
require 'fat_free_crm/migration/authz_matrix'

# The matrix itself is asserted against PostgreSQL in spec/lib/tasks/authz_matrix_spec.rb; these
# examples pin the guard rails that must hold before any seed is written, on any adapter.
describe FatFreeCRM::Migration::AuthzMatrix do
  def connection_double(adapter_name:, counts: Hash.new(0))
    connection = instance_double(ActiveRecord::ConnectionAdapters::AbstractAdapter, adapter_name: adapter_name)
    allow(connection).to receive(:quote_table_name) { |name| %("#{name}") }
    allow(connection).to receive(:select_value) do |sql|
      table = sql[/FROM "([a-z_]+)"/, 1]
      counts.fetch(table, 0)
    end
    connection
  end

  it "covers every table the Spring fixture loads and every actor the Spring tests use" do
    expect(described_class::TABLES).to contain_exactly(
      "users", "groups", "groups_users", "permissions", "accounts", "campaigns", "contacts", "leads",
      "opportunities", "tasks", "comments", "emails"
    )
    expect(described_class::ACTORS).to eq(
      %w[owner assignee shared_user group_member completer stale_member unrelated admin]
    )
    expect(described_class::REGENERATE).to include("rake ffcrm:migration:authz_matrix")
  end

  it "refuses to run on anything but PostgreSQL before touching any table" do
    connection = connection_double(adapter_name: "SQLite")
    expect(ActiveRecord::Base).not_to receive(:transaction)

    expect { described_class.new(connection).generate }
      .to raise_error(RuntimeError, /requires PostgreSQL/)
    expect(connection).not_to have_received(:select_value)
  end

  it "refuses to seed a database whose tables are not empty, naming the table" do
    connection = connection_double(adapter_name: "PostgreSQL", counts: { "permissions" => 3 })
    expect(ActiveRecord::Base).not_to receive(:transaction)

    expect { described_class.new(connection).generate }
      .to raise_error(RuntimeError, /permissions must be empty before authz matrix generation/)
  end

  it "checks every table before reaching the first non-empty one" do
    connection = connection_double(adapter_name: "PostgreSQL", counts: { "emails" => 1 })

    expect { described_class.new(connection).generate }
      .to raise_error(RuntimeError, /emails must be empty/)
    described_class::TABLES.each do |table|
      expect(connection).to have_received(:select_value).with(a_string_including(%("#{table}")))
    end
  end
end
