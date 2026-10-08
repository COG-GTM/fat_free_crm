# frozen_string_literal: true

require "spec_helper"

# The deployment-only `custom_fields` JSONB column (owned by the Spring Boot side and kept in sync by a
# database trigger) must be invisible to ActiveRecord so Rails never reads, writes or clobbers it.
describe "custom_fields ignored column" do # rubocop:disable RSpec/DescribeClass
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
    entities.map(&:first).each do |klass|
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

  it "registers custom_fields as an ignored column exactly once per has_fields model" do
    entities.map(&:first).each do |klass|
      expect(klass.ignored_columns).to include("custom_fields"), klass.name
      klass.has_fields
      klass.has_fields
      expect(klass.ignored_columns.count("custom_fields")).to eq(1), klass.name
    end
  end

  it "hides the column from the model's attributes while it still exists in the schema" do
    entities.each do |klass, factory|
      expect(klass.connection.column_exists?(klass.table_name, :custom_fields)).to be(true), klass.name
      expect(klass.column_names).not_to include("custom_fields"), klass.name
      expect(klass.attribute_names).not_to include("custom_fields"), klass.name
      record = build(factory)
      expect(record).not_to respond_to(:custom_fields), klass.name
      expect(record.has_attribute?(:custom_fields)).to be(false), klass.name
      expect { klass.new(custom_fields: "{}") }.to raise_error(ActiveModel::UnknownAttributeError), klass.name
    end
  end

  it "leaves a value written to the column by the database untouched when Rails saves the record" do
    entities.each do |klass, factory|
      record = create(factory)
      document = '{"cf_region": "north"}'
      klass.connection.execute(
        "UPDATE #{klass.connection.quote_table_name(klass.table_name)} " \
        "SET custom_fields = #{klass.connection.quote(document)} WHERE id = #{record.id.to_i}"
      )

      record.reload
      record.updated_at = 1.minute.from_now
      expect(record.save!).to be(true), klass.name
      record.touch

      stored = klass.connection.select_value(
        "SELECT custom_fields FROM #{klass.connection.quote_table_name(klass.table_name)} WHERE id = #{record.id.to_i}"
      )
      expect(stored).to eq(document), klass.name
    end
  end
end
