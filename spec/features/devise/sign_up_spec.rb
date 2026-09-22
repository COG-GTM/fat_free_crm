# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'features/acceptance_helper'

feature 'Devise Sign-up' do
  background do
    Setting.user_signup = :allowed
  end

  scenario 'with valid credentials' do
    visit "/users/sign_up"

    fill_in "user[email]", with: "john@example.com"
    fill_in "user[username]", with: "john"
    fill_in "user[password]", with: "password"
    fill_in "user[password_confirmation]", with: "password"
    click_button("Sign Up")

    expect(current_path).to eq "/users/sign_in"
    expect(page).to have_content("A message with a confirmation link has been sent to your email address. Please follow the link to activate your account.")
  end

  scenario 'without credentials' do
    visit "/users/sign_up"
    click_button("Sign Up")

    expect(page).to have_content("6 errors prohibited this User from being saved")
    expect(page).to have_content("Please specify email address")
    expect(page).to have_content("Email is too short (minimum is 3 characters)")
    expect(page).to have_content("Email is invalid")
    expect(page).to have_content("Please specify username")
    expect(page).to have_content("Username is invalid")
    expect(page).to have_content("Password can't be blank")
  end

  scenario 'shows the sign up link on the login page when signup is allowed' do
    visit "/users/sign_in"

    expect(page).to have_link("Sign Up Now!", href: "/users/sign_up")
    click_link "Sign Up Now!"
    expect(current_path).to eq "/users/sign_up"
    expect(page).to have_button("Sign Up")
  end

  context 'when user signup needs approval' do
    background do
      Setting.user_signup = :needs_approval
    end

    scenario 'shows the sign up link on the login page' do
      visit "/users/sign_in"

      expect(page).to have_link("Sign Up Now!", href: "/users/sign_up")
    end

    scenario 'lets a visitor sign up and creates a suspended account' do
      visit "/users/sign_up"

      fill_in "user[email]", with: "jane@example.com"
      fill_in "user[username]", with: "jane"
      fill_in "user[password]", with: "password"
      fill_in "user[password_confirmation]", with: "password"
      click_button("Sign Up")

      expect(current_path).to eq "/users/sign_in"
      expect(page).to have_content("A message with a confirmation link has been sent to your email address.")

      user = User.find_by(username: "jane")
      expect(user).to be_present
      expect(user).to be_suspended
    end
  end

  context 'when user signup is not allowed' do
    background do
      Setting.user_signup = :not_allowed
    end

    scenario 'redirects the /signup shortcut to the login page' do
      visit "/signup"

      expect(current_path).to eq "/users/sign_in"
      expect(page).to have_button("Login")
    end

    scenario 'does not create a user when the sign up form is submitted with invalid data' do
      page.driver.submit :post, "/users", user: {
        email: "",
        username: "",
        password: "",
        password_confirmation: ""
      }

      expect(current_path).to eq "/users/sign_in"
      expect(page).to have_no_content("prohibited this User from being saved")
    end

    scenario 'hides the sign up link on the login page' do
      visit "/users/sign_in"

      expect(page).to have_button("Login")
      expect(page).to have_no_link("Sign Up Now!")
      expect(page).to have_no_content("Do not have an account?")
    end

    scenario 'redirects the sign up page to the login page' do
      visit "/users/sign_up"

      expect(current_path).to eq "/users/sign_in"
      expect(page).to have_button("Login")
      expect(page).to have_no_button("Sign Up")
    end

    scenario 'does not create a user when the sign up form is submitted directly' do
      page.driver.submit :post, "/users", user: {
        email: "john@example.com",
        username: "john",
        password: "password",
        password_confirmation: "password"
      }

      expect(current_path).to eq "/users/sign_in"
      expect(User.find_by(username: "john")).to be_nil
      expect(page).to have_no_content("A message with a confirmation link has been sent to your email address.")
    end
  end
end
