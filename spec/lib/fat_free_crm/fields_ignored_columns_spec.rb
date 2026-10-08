# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require "spec_helper"

describe "FatFreeCRM::Fields custom_fields ignored column" do # rubocop:disable RSpec/DescribeClass
  let(:entities) { [Account, Campaign, Contact, Lead, Opportunity, Task] }

  it "hides the deployment JSONB column on every model declaring has_fields" do
    entities.each do |klass|
      expect(klass.ignored_columns).to include("custom_fields"), klass.name
    end
  end

  it "does not hide the column on models without custom fields" do
    expect(User.ignored_columns).not_to include("custom_fields")
  end

  it "is idempotent when has_fields is declared again" do
    ignored_before = Account.ignored_columns.count("custom_fields")
    validators_before = Account._validate_callbacks.to_a.size
    serialized_before = Account.attribute_types.keys

    Account.has_fields

    expect(Account.ignored_columns.count("custom_fields")).to eq(ignored_before)
    expect(Account._validate_callbacks.to_a.size).to eq(validators_before)
    expect(Account.attribute_types.keys).to eq(serialized_before)
  end

  context "when the physical custom_fields column exists" do
    before do
      @added_columns = []
      entities.each do |klass|
        connection = klass.connection
        next if connection.column_exists?(klass.table_name, :custom_fields)

        connection.add_column(klass.table_name, :custom_fields, :text)
        @added_columns << [klass, klass.table_name]
        klass.reset_column_information
      end
    end

    after do
      @added_columns.each do |klass, table|
        connection = klass.connection
        connection.remove_column(table, :custom_fields) if connection.column_exists?(table, :custom_fields)
        klass.reset_column_information
      end
    end

    it "keeps the column out of the attribute set and rejects mass assignment to it" do
      entities.each do |klass|
        expect(klass.column_names).not_to include("custom_fields"), klass.name
        expect(klass.new.attribute_names).not_to include("custom_fields"), klass.name
        expect(klass.new).not_to respond_to(:custom_fields), klass.name
        expect { klass.new(custom_fields: "{}") }
          .to raise_error(ActiveModel::UnknownAttributeError), klass.name
      end
    end
  end
end
