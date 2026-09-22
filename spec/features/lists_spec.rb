# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path('acceptance_helper.rb', __dir__)

feature 'Saved lists', '
  In order to quickly return to a filtered view
  As a user
  I want to save the current view as a list in the sidebar
' do
  before(:each) do
    do_login_if_not_already(first_name: 'Bill', last_name: 'Murray')
  end

  scenario 'should link saved lists to their stored relative url' do
    create(:list, name: 'Hot leads', url: '/leads?q%5Bs%5D=first_name+asc')
    create(:list, name: 'My contacts', url: '/contacts?page=2', user: @user)

    visit leads_page

    within '#lists' do
      expect(page).to have_link('Hot leads', href: '/leads?q%5Bs%5D=first_name+asc')
      expect(page).to have_link('My contacts', href: '/contacts?page=2')
    end

    click_link 'My contacts'
    expect(page).to have_current_path('/contacts?page=2')
  end

  scenario 'should save the current advanced search as a list', :js do
    create(:lead, first_name: 'Mr', last_name: 'Lead')

    visit leads_path(q: { first_name_cont: 'Mr' })
    expect(leads_element).to have_content('Mr Lead')

    within global_lists_panel do
      click_link 'Make current view a list'
      fill_in 'list[name]', with: 'Mister leads'
      click_button 'save'
    end

    within '#lists' do
      expect(page).to have_link('Mister leads')
      expect(find_link('Mister leads')[:href]).to match(%r{\A(https?://[^/]+)?/leads\?})
    end

    list = List.find_by(name: 'Mister leads')
    expect(list.user_id).to eq(nil)
    expect(list.url).to match(List::RELATIVE_PATH_FORMAT)
    expect(list.url).to start_with('/leads?')
    expect(list).to be_valid
  end

  def leads_element
    find_by_id('leads')
  end

  def global_lists_panel
    first('#lists .panel.lists')
  end
end
