# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'features/acceptance_helper'

feature 'Devise Sign-up when user_signup is not allowed' do
  background do
    Setting.user_signup = :not_allowed
  end

  after do
    Setting.where(name: "user_signup").delete_all
    Setting.clear_cache!
  end

  scenario 'visiting the sign up page redirects to sign in with an explanation' do
    visit "/users/sign_up"

    expect(page).to have_current_path("/users/sign_in")
    expect(page).to have_content("User signup is not allowed. Please contact your system administrator.")
    expect(page).to have_button("Login")
    expect(page).to have_no_field("user[username]")
    expect(page).to have_no_button("Sign Up")
  end

  scenario 'the sign in page does not offer a sign up link' do
    visit "/users/sign_in"

    expect(page).to have_button("Login")
    expect(page).to have_no_link("Sign Up Now!")
    expect(page).to have_no_content("Do not have an account?")
  end

  scenario 'the legacy /signup route also ends on the sign in page' do
    visit "/signup"

    expect(page).to have_current_path("/users/sign_in")
    expect(page).to have_content("User signup is not allowed. Please contact your system administrator.")
  end

  scenario 'submitting the registration form directly does not create a user' do
    expect do
      page.driver.post "/users", user: { username: "john", email: "john@example.com",
                                         password: "password", password_confirmation: "password" }
    end.not_to change(User, :count)

    expect(User.find_by(username: "john")).to be_nil
  end

  scenario 'switching the setting back to allowed re-enables sign up' do
    Setting.user_signup = :allowed

    visit "/users/sign_up"

    expect(page).to have_current_path("/users/sign_up")
    expect(page).to have_field("user[username]")
    expect(page).to have_button("Sign Up")
  end
end
