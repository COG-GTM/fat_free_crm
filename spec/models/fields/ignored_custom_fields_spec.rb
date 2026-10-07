# frozen_string_literal: true

require "spec_helper"
require "json"

describe "custom_fields Rails column hiding" do # rubocop:disable RSpec/DescribeClass
  let(:entities) do
    [
      [Account, :account],
      [Campaign, :campaign],
      [Contact, :contact],
      [Lead, :lead],
      [Opportunity, :opportunity],
      [Task, :task]
    ]
  end

  before do
    @added_columns = []
    entities.each do |entity|
      klass = entity.first
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

  it "omits the deployment JSONB column from unsaved and persisted JSON" do
    entities.each do |klass, factory|
      unsaved = build(factory)
      expect(unsaved.as_json).not_to have_key("custom_fields"), klass.name

      saved = create(factory)
      expect(saved.as_json).not_to have_key("custom_fields"), klass.name
      expect(JSON.parse(saved.to_json)).not_to have_key("custom_fields"), klass.name
    end
  end
end
