# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
# == Schema Information
#
# Table name: fields
#
#  id             :integer         not null, primary key
#  type           :string(255)
#  field_group_id :integer
#  position       :integer
#  name           :string(64)
#  label          :string(128)
#  hint           :string(255)
#  placeholder    :string(255)
#  as             :string(32)
#  collection     :text
#  disabled       :boolean
#  required       :boolean
#  maxlength      :integer
#  created_at     :datetime
#  updated_at     :datetime
#

require File.expand_path(File.dirname(__FILE__) + '/../../spec_helper')

describe CustomField do
  it "should add a column to the database" do
    expect(CustomField.connection).to receive(:add_column)
      .with("contacts", "cf_test_field", 'string')
    expect(Contact).to receive(:reset_column_information)
    expect(Contact).to receive(:serialize_custom_fields!)

    create(:custom_field,
           as: "string",
           name: "cf_test_field",
           label: "Test Field",
           field_group: create(:field_group, klass_name: "Contact"))
  end

  it "should add a column to the database and include column_options" do
    expect(CustomField.connection).to receive(:add_column)
      .with("contacts", "cf_test_field", 'decimal', precision: 15, scale: 2)
    expect(Contact).to receive(:reset_column_information)
    expect(Contact).to receive(:serialize_custom_fields!)

    create(:custom_field,
           as: "decimal",
           name: "cf_test_field",
           label: "Test Field",
           field_group: create(:field_group, klass_name: "Contact"))
  end

  it "should generate a unique column name for a custom field" do
    field_group = build(:field_group, klass_name: "Contact")
    c = build(:custom_field, label: "Test Field", field_group: field_group)

    columns = []
    %w[cf_test_field cf_test_field_2 cf_test_field_3 cf_test_field_4].each do |field|
      expect(c.send(:generate_column_name)).to eq(field)
      allow(Contact).to receive(:column_names).and_return(columns << field)
    end
  end

  it "should evaluate the safety of database transitions" do
    c = build(:custom_field, as: "string")
    expect(c.send(:db_transition_safety, c.as, "email")).to eq(:null)
    expect(c.send(:db_transition_safety, c.as, "text")).to eq(:safe)
    expect(c.send(:db_transition_safety, c.as, "datetime")).to eq(:unsafe)

    c = build(:custom_field, as: "datetime")
    expect(c.send(:db_transition_safety, c.as, "date")).to eq(:safe)
    expect(c.send(:db_transition_safety, c.as, "url")).to eq(:unsafe)
  end

  it "should return a safe list of types for the 'as' select options" do
    { "email"   => %w[check_boxes text string email url tel select radio_buttons],
      "integer" => %w[integer float] }.each do |type, expected_arr|
      c = build(:custom_field, as: type)
      opts = c.available_as
      expect(opts.map(&:first)).to match_array(expected_arr)
    end
  end

  it "should change a column's type for safe transitions" do
    expect(CustomField.connection).to receive(:add_column)
      .with("contacts", "cf_test_field", 'string')
    expect(CustomField.connection).to receive(:change_column)
      .with("contacts", "cf_test_field", 'text')
    expect(Contact).to receive(:reset_column_information).twice
    expect(Contact).to receive(:serialize_custom_fields!).twice

    field_group = create(:field_group, klass_name: "Contact")
    c = create(:custom_field,
               label: "Test Field",
               name: nil,
               as: "email",
               field_group: field_group)
    c.as = "text"
    c.save
  end

  describe "in case a new custom field was added by a different instance" do
    it "should refresh column info and retry on assignment error" do
      expect(Contact).to receive(:reset_column_information)

      expect { Contact.new cf_unknown_field: 123 }.to raise_error(ActiveRecord::UnknownAttributeError)
    end

    it "should refresh column info and retry on attribute error" do
      expect(Contact).to receive(:reset_column_information)
      expect(Contact).to receive(:serialize_custom_fields!)

      contact = build(:contact)
      expect(contact.cf_another_new_field).to eq(nil)
    end
  end

  describe "validation" do
    it "should have errors if custom field is required" do
      event = CustomField.new(name: 'cf_event', required: true)
      foo = double(cf_event: nil)
      err = double(:errors)
      allow(err).to receive(:add)
      expect(foo).to receive(:errors).and_return(err)
      event.custom_validator(foo)
    end

    it "should have errors if custom field is longer than maxlength" do
      event = CustomField.new(name: 'cf_event', maxlength: 5)
      foo = double(cf_event: "This is too long")
      err = double(:errors)
      allow(err).to receive(:add)
      expect(foo).to receive(:errors).and_return(err)
      event.custom_validator(foo)
    end
  end

  describe "column_options" do
    it "returns symbol-keyed options for a decimal field" do
      options = build(:custom_field, as: "decimal").send(:column_options)

      expect(options).to eq(precision: 15, scale: 2)
      expect(options.keys).to all(be_a(Symbol))
    end

    it "symbolizes the string keys held by the indifferent-access field type registry" do
      registry_keys = Field.field_types["decimal"][:column_options].keys
      expect(registry_keys).to all(be_a(String))

      options = build(:custom_field, as: "decimal").send(:column_options)
      expect(options.keys.map(&:to_s)).to eq(registry_keys)
    end

    it "returns an empty hash for field types without column options" do
      %w[string text email url tel select radio_buttons check_boxes boolean date datetime integer float].each do |as|
        expect(build(:custom_field, as: as).send(:column_options)).to eq({}), as
      end
    end
  end

  describe "against the live database" do
    let(:field_group) { create(:field_group, klass_name: "Contact", tag: nil) }

    after do
      %w[cf_live_amount cf_live_interests cf_live_count].each do |name|
        Contact.connection.remove_column(:contacts, name) if Contact.connection.column_exists?(:contacts, name)
      end
      Contact.reset_column_information
    end

    it "adds a decimal column with the configured precision and scale" do
      create(:custom_field, label: "Live amount", as: "decimal", field_group: field_group)

      column = Contact.columns_hash.fetch("cf_live_amount")
      expect(column.type).to eq(:decimal)
      expect(column.precision).to eq(15)
      expect(column.scale).to eq(2)
    end

    it "round-trips a decimal value through the new column" do
      create(:custom_field, label: "Live amount", as: "decimal", field_group: field_group)
      contact = create(:contact)

      contact.update!(cf_live_amount: BigDecimal("12345.67"))

      expect(Contact.find(contact.id).cf_live_amount).to eq(BigDecimal("12345.67"))
    end

    it "stores check_boxes values as a YAML-serialized array" do
      create(:custom_field, label: "Live interests", as: "check_boxes",
                            collection: %w[Email Events], field_group: field_group)
      Contact.serialize_custom_fields!
      contact = create(:contact)

      contact.update!(cf_live_interests: %w[Email Events])

      expect(Contact.find(contact.id).cf_live_interests).to eq(%w[Email Events])
      raw = Contact.connection.select_value(Contact.where(id: contact.id).select(:cf_live_interests).to_sql)
      expect(YAML.safe_load(raw)).to eq(%w[Email Events])
    end

    it "reads an unset check_boxes field as an empty array" do
      create(:custom_field, label: "Live interests", as: "check_boxes", field_group: field_group)
      Contact.serialize_custom_fields!
      contact = create(:contact)

      expect(Contact.find(contact.id).cf_live_interests).to eq([])
    end

    it "changes the column type for a safe transition without losing data" do
      field = create(:custom_field, label: "Live count", as: "integer", field_group: field_group)
      contact = create(:contact)
      contact.update!(cf_live_count: 7)

      field.update!(as: "float")

      expect(Contact.columns_hash.fetch("cf_live_count").type).to eq(:float)
      expect(Contact.find(contact.id).cf_live_count).to eq(7.0)
    end
  end
end
