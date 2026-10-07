# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path('../acceptance_helper.rb', __dir__)

feature 'Custom Fields tab', '
  In order to capture extra information about contacts
  As an administrator
  I want to create decimal and checkbox-list custom fields and use them on contacts
' do
  before(:each) do
    do_login(first_name: 'Captain', last_name: 'Kirk', admin: true)
  end

  after(:each) do
    %w[cf_deal_value cf_contact_channels].each do |column|
      Contact.connection.remove_column(:contacts, column) if Contact.connection.column_exists?(:contacts, column)
    end
    reload_contact_schema
  end

  # The admin UI asks you to restart the Rails server after adding a custom
  # field so that every connection picks up the new column and its
  # serialization. The test server runs inside this process, so emulate that.
  def reload_contact_schema
    ActiveRecord::Base.connection_pool.connections.each(&:clear_cache!)
    Contact.reset_column_information
    Contact.serialize_custom_fields!
  end

  def open_create_field_form(field_group)
    within("#field_group_#{field_group.id}") do
      find('.subtitle_tools a.create').click
      expect(page).to have_field('field[label]', visible: :visible)
    end
  end

  def expand_field_group(field_group)
    click_link field_group.label if page.has_link?(field_group.label)
  end

  def custom_field_value(label)
    find(:xpath, "//th[normalize-space(text())='#{label}']/following-sibling::td[1]").text
  end

  def contacts_element
    find_by_id('contacts')
  end

  scenario 'should create a decimal custom field and use it on a contact', :js do
    field_group = create(:field_group, klass_name: 'Contact', label: 'Deal Terms', tag: nil)

    visit admin_fields_path
    open_create_field_form(field_group)
    within("#field_group_#{field_group.id}") do
      fill_in 'field[label]', with: 'Deal Value'
      select 'Number (Decimal)', from: 'field[as]'
      click_button 'Create field'
      expect(page).to have_content('Deal Value')
      expect(page).to have_content('Number (Decimal)')
    end
    expect(Contact.column_names).to include('cf_deal_value')
    reload_contact_schema

    visit contacts_page
    click_link 'Create Contact'
    expect(page).to have_css('#contact_first_name', visible: :visible)
    fill_in 'contact_first_name', with: 'Jean-Luc'
    fill_in 'contact_last_name', with: 'Picard'
    expand_field_group(field_group)
    fill_in 'contact[cf_deal_value]', with: '1234.56'
    click_button 'Create Contact'
    expect(contacts_element).to have_content('Jean-Luc Picard')

    contacts_element.click_link 'Jean-Luc Picard'
    expect(page).to have_content('Deal Terms')
    expect(custom_field_value('Deal Value')).to eq('1234.56')
  end

  scenario 'should create a checkbox list custom field and use it on a contact', :js do
    field_group = create(:field_group, klass_name: 'Contact', label: 'Preferences', tag: nil)

    visit admin_fields_path
    open_create_field_form(field_group)
    within("#field_group_#{field_group.id}") do
      fill_in 'field[label]', with: 'Contact Channels'
      select 'Checkbox List', from: 'field[as]'
      expect(page).to have_field('field[collection_string]')
      fill_in 'field[collection_string]', with: 'Email|Phone|SMS'
      click_button 'Create field'
      expect(page).to have_content('Contact Channels')
      expect(page).to have_content('Checkbox List')
    end
    expect(Contact.column_names).to include('cf_contact_channels')
    reload_contact_schema

    visit contacts_page
    click_link 'Create Contact'
    expect(page).to have_css('#contact_first_name', visible: :visible)
    fill_in 'contact_first_name', with: 'William'
    fill_in 'contact_last_name', with: 'Riker'
    expand_field_group(field_group)
    check 'Email'
    check 'SMS'
    click_button 'Create Contact'
    expect(contacts_element).to have_content('William Riker')

    contacts_element.click_link 'William Riker'
    expect(page).to have_content('Preferences')
    expect(custom_field_value('Contact Channels')).to eq('Email, SMS')
  end

  scenario 'should show selected checkbox list values on the contact page' do
    field_group = create(:field_group, klass_name: 'Contact', label: 'Preferences', tag: nil)
    CustomField.create!(field_group: field_group, label: 'Contact Channels', as: 'check_boxes', collection: %w[Email Phone SMS])
    reload_contact_schema
    contact = create(:contact, first_name: 'Deanna', last_name: 'Troi')
    contact.update!(cf_contact_channels: ['', 'Phone', 'SMS'])

    visit contact_path(contact)
    expect(page).to have_content('Deanna Troi')
    expect(page).to have_content('Preferences')
    expect(custom_field_value('Contact Channels')).to eq('Phone, SMS')
  end
end
