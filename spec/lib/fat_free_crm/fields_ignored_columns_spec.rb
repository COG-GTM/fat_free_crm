# frozen_string_literal: true

require "spec_helper"

describe "FatFreeCRM::Fields.has_fields ignored columns" do # rubocop:disable RSpec/DescribeClass
  let(:has_fields_models) { [Account, Campaign, Contact, Lead, Opportunity, Task] }

  it "marks custom_fields as an ignored column on every has_fields model" do
    has_fields_models.each do |klass|
      expect(klass.ignored_columns).to include("custom_fields"), "#{klass} should ignore custom_fields"
    end
  end

  it "does not duplicate the ignored column when has_fields is invoked again" do
    has_fields_models.each do |klass|
      klass.has_fields
      expect(klass.ignored_columns.count("custom_fields")).to eq(1), "#{klass} ignored_columns duplicated"
    end
  end

  it "keeps the custom_fields column out of the attribute set even when it exists in the schema" do
    klass = Account
    connection = klass.connection
    added = false
    unless connection.column_exists?(klass.table_name, :custom_fields)
      connection.add_column(klass.table_name, :custom_fields, :text)
      added = true
    end
    klass.reset_column_information

    expect(klass.column_names).not_to include("custom_fields")
    expect(klass.attribute_names).not_to include("custom_fields")
    account = klass.new(name: "Ignored columns", user: create(:user))
    expect(account.save).to be(true)
    expect(account.reload.attributes).not_to have_key("custom_fields")
  ensure
    if added
      connection.remove_column(klass.table_name, :custom_fields)
      klass.reset_column_information
    end
  end

  it "does not add custom_fields to ignored_columns on models that do not call has_fields" do
    expect(User.ignored_columns).not_to include("custom_fields")
  end
end
