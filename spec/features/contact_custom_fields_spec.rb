# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path('acceptance_helper.rb', __dir__)

feature 'Contact Custom Fields', '
  In order to keep deployment-specific data visible
  As a user
  I want decimal and multi-select custom fields rendered on the contact page
' do
  before :each do
    do_login(admin: true)
  end

  after do
    %w[cf_annual_value cf_interests].each do |name|
      Contact.connection.remove_column(:contacts, name) if Contact.connection.column_exists?(:contacts, name)
    end
    Contact.reset_column_information
  end

  scenario 'shows decimal and check_boxes custom field values on the contact page' do
    group = FieldGroup.create!(klass_name: 'Contact', label: 'Phase 0 Contact Info')
    CustomField.create!(field_group: group, label: 'Annual value', as: 'decimal')
    CustomField.create!(field_group: group, label: 'Interests', as: 'check_boxes',
                        collection: %w[Email Events Webinars])
    Contact.serialize_custom_fields!

    contact = create(:contact, user: @user, first_name: 'Census', last_name: 'Subject')
    contact.update!(cf_annual_value: BigDecimal('12345.67'), cf_interests: %w[Email Events])

    visit contact_path(contact)

    expect(page).to have_content('Census Subject')
    expect(page).to have_content('Phase 0 Contact Info')
    expect(page).to have_content('Annual value')
    expect(page).to have_content('12345.67')
    expect(page).to have_content('Interests')
    expect(page).to have_content('Email, Events')
  end
end
