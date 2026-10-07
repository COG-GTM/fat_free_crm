# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../../spec_helper')

# Rails 8 passes column options to +add_column+/+change_column+ as keyword
# arguments, so CustomField#column_options must hand back Symbol keys even
# though Field.field_types stores them with indifferent (String) access.
describe CustomField do
  def remove_contact_column(name)
    connection = Contact.connection
    connection.remove_column(:contacts, name) if connection.column_exists?(:contacts, name)
    Contact.reset_column_information
  end

  describe "#column_options" do
    it "returns a plain Hash with Symbol keys for decimal fields" do
      options = CustomField.new(as: 'decimal').send(:column_options)

      expect(options).to be_an_instance_of(Hash)
      expect(options).to eq(precision: 15, scale: 2)
      expect(options.keys).to all(be_a(Symbol))
    end

    it "symbolizes the String keys stored in the indifferent-access field type registry" do
      expect(Field.field_types['decimal'][:column_options].keys).to all(be_a(String))

      expect(CustomField.new(as: 'decimal').send(:column_options).keys).to all(be_a(Symbol))
    end

    it "returns an empty Hash for field types without column options" do
      Field.field_types.each_key do |as|
        next if as == 'decimal'

        expect(CustomField.new(as: as).send(:column_options)).to eq({}), "expected no column options for #{as}"
      end
    end

    it "symbolizes column options of field types registered at runtime" do
      Field.register(as: 'spec_money', klass: 'CustomField', type: 'decimal',
                     column_options: { 'precision' => 10, 'scale' => 4 })

      expect(CustomField.new(as: 'spec_money').send(:column_options)).to eq(precision: 10, scale: 4)
    ensure
      Field.field_types.delete('spec_money')
    end
  end

  describe "live column DDL" do
    let(:field_group) { create(:field_group, klass_name: 'Contact') }

    after do
      remove_contact_column(:cf_spec_deal_value)
      remove_contact_column(:cf_spec_channels)
    end

    it "adds a decimal column with the configured precision and scale" do
      create(:custom_field, as: 'decimal', label: 'Spec deal value', field_group: field_group)
      Contact.reset_column_information

      column = Contact.columns_hash.fetch('cf_spec_deal_value')
      expect(column.type).to eq(:decimal)
      expect(column.precision).to eq(15)
      expect(column.scale).to eq(2)
    end

    it "round-trips decimal values through the new column" do
      create(:custom_field, as: 'decimal', label: 'Spec deal value', field_group: field_group)
      Contact.reset_column_information

      contact = create(:contact)
      contact.update!(cf_spec_deal_value: '1234.567')

      expect(contact.reload.cf_spec_deal_value).to eq(BigDecimal('1234.57'))
      expect(Contact.where(cf_spec_deal_value: BigDecimal('1234.57')).pluck(:id)).to eq([contact.id])
    end

    it "changes the column type with keyword column options on a safe transition" do
      field = create(:custom_field, as: 'integer', label: 'Spec deal value', field_group: field_group)
      Contact.reset_column_information
      expect(Contact.columns_hash.fetch('cf_spec_deal_value').type).to eq(:integer)

      field.update!(as: 'float')
      Contact.reset_column_information

      expect(Contact.columns_hash.fetch('cf_spec_deal_value').type).to eq(:float)
    end

    it "serializes a check_boxes column as an Array and round-trips the values" do
      create(:custom_field, as: 'check_boxes', label: 'Spec channels',
                            collection: %w[Email Phone SMS], field_group: field_group)
      Contact.reset_column_information
      Contact.serialize_custom_fields!

      type = Contact.attribute_types.fetch('cf_spec_channels')
      expect(type).to be_a(ActiveRecord::Type::Serialized)
      expect(type.coder.object_class).to eq(Array)

      contact = create(:contact)
      expect(contact.cf_spec_channels).to eq([])

      contact.update!(cf_spec_channels: %w[Email SMS])
      expect(contact.reload.cf_spec_channels).to eq(%w[Email SMS])

      raw = Contact.connection.select_value(
        Contact.where(id: contact.id).select(:cf_spec_channels).to_sql
      )
      expect(YAML.safe_load(raw)).to eq(%w[Email SMS])
    end

    it "rejects non-Array values for a check_boxes column" do
      create(:custom_field, as: 'check_boxes', label: 'Spec channels',
                            collection: %w[Email Phone SMS], field_group: field_group)
      Contact.reset_column_information
      Contact.serialize_custom_fields!

      expect { build(:contact).cf_spec_channels = 'Email' }
        .to raise_error(ActiveRecord::SerializationTypeMismatch)
    end
  end
end
