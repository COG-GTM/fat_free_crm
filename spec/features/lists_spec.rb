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
  I want to save the current view as a list without being exposed to malicious links
' do
  let(:filtered_leads_url) { "#{leads_path}?q%5Bfirst_name_cont%5D=Hot" }
  let(:xss_url) { 'javascript:alert(document.cookie)' }

  before(:each) do
    do_login_if_not_already(first_name: 'Bill', last_name: 'Murray')
  end

  # Simulates a tampered client: the hidden list[url] field is normally filled
  # from window.location by lists.js.coffee on submit, so that handler is
  # removed and the field is set directly before the form is submitted.
  def submit_list_form_with_url(panel, name, url)
    within(panel) do
      click_link 'Make current view a list'
      fill_in 'list[name]', with: name
    end
    page.execute_script("$(document).off('click', '.lists .list_form [type=submit]')")
    page.execute_script("$('#{panel} .list_form [name=\"list[url]\"]').val(#{url.to_json})")
    within(panel) { click_button 'save' }
  end

  # On a rejected save the server response keeps the form open, re-enables
  # the submit button and puts the focus back on the name field.
  def expect_list_form_rejected(panel)
    within(panel) do
      expect(page).to have_css(".list_form [name='list[name]']:focus")
      expect(page).to have_button('save', disabled: false)
    end
  end

  scenario 'should save the current filtered view as a personal list', :js do
    visit filtered_leads_url
    within('.lists', text: 'My lists') do
      click_link 'Make current view a list'
      fill_in 'list[name]', with: 'Hot leads'
      click_button 'save'
    end

    within('.lists', text: 'My lists') do
      expect(page).to have_link('Hot leads', href: %r{/leads\?})
    end
    list = List.find_by(name: 'Hot leads')
    expect(list.user_id).to eq(@user.id)
    expect(list.url).to start_with('/leads?')
  end

  scenario 'should reject a javascript: url when saving a personal list', :js do
    visit filtered_leads_url
    submit_list_form_with_url('.lists:last-child', 'Evil list', xss_url)

    expect_list_form_rejected('.lists:last-child')
    within('.lists', text: 'My lists') do
      expect(page).to have_content('No saved lists')
      expect(page).to have_no_link('Evil list')
    end
    expect(List.where(name: 'Evil list')).to be_empty
    expect(page).to have_no_css("a[href^='javascript:']")
  end

  scenario 'should reject a javascript: url when saving a global list', :js do
    visit filtered_leads_url
    submit_list_form_with_url('.lists:first-child', 'Evil global list', xss_url)

    expect_list_form_rejected('.lists:first-child')
    within('.lists', text: 'Global lists') do
      expect(page).to have_content('No saved lists')
      expect(page).to have_no_link('Evil global list')
    end
    expect(List.where(name: 'Evil global list')).to be_empty
    expect(page).to have_no_css("a[href^='javascript:']")
  end

  scenario 'should keep the original url when overwriting a list with a javascript: url', :js do
    list = create(:list, name: 'Hot leads', url: '/leads?q%5Blast_name_cont%5D=Lead', user: @user)

    visit filtered_leads_url
    within('.lists', text: 'My lists') do
      expect(page).to have_link('Hot leads', href: list.url)
    end
    submit_list_form_with_url('.lists:last-child', 'Hot leads', xss_url)

    expect_list_form_rejected('.lists:last-child')
    within('.lists', text: 'My lists') do
      expect(page).to have_link('Hot leads', href: list.url)
    end
    expect(list.reload.url).to eq('/leads?q%5Blast_name_cont%5D=Lead')
    expect(page).to have_no_css("a[href^='javascript:']")
  end

  scenario 'should reject an absolute external url when saving a personal list', :js do
    visit filtered_leads_url
    submit_list_form_with_url('.lists:last-child', 'Phishing list', 'http://evil.example.com/')

    expect_list_form_rejected('.lists:last-child')
    within('.lists', text: 'My lists') do
      expect(page).to have_no_link('Phishing list')
    end
    expect(List.where(name: 'Phishing list')).to be_empty
  end

  scenario 'should link saved lists to their stored relative url' do
    create(:list, name: 'Hot leads', url: '/leads?q%5Bs%5D=first_name+asc')
    create(:list, name: 'My contacts', url: '/contacts?page=2', user: @user)

    visit leads_page

    within('.lists', text: 'Global lists') do
      expect(page).to have_link('Hot leads', href: '/leads?q%5Bs%5D=first_name+asc')
    end
    within('.lists', text: 'My lists') do
      expect(page).to have_link('My contacts', href: '/contacts?page=2')
    end

    click_link 'My contacts'
    expect(page).to have_current_path('/contacts?page=2')
  end
end
