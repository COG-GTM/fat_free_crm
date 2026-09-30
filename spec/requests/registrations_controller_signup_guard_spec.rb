# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'spec_helper'

RSpec.describe RegistrationsController do
  let(:params) do
    { user: { username: "newuser", email: "newuser@example.com",
              password: "password", password_confirmation: "password" } }
  end
  let(:not_allowed_message) { 'User signup is not allowed. Please contact your system administrator.' }

  before { Rack::Attack.cache.store = ActiveSupport::Cache::MemoryStore.new }
  after { Rack::Attack.cache.store = Rails.cache }

  describe "unexpected Setting.user_signup values fail closed" do
    [nil, "", :unknown, "allowed", "needs_approval"].each do |value|
      context "when user_signup is #{value.inspect}" do
        before { allow(Setting).to receive(:user_signup).and_return(value) }

        it "redirects GET /users/sign_up to sign in" do
          get new_user_registration_path
          expect(response).to redirect_to(new_user_session_path)
          expect(flash[:alert]).to eq(not_allowed_message)
        end

        it "does not create a user on POST /users" do
          expect { post user_registration_path, params: params }.not_to change(User, :count)
          expect(response).to redirect_to(new_user_session_path)
        end
      end
    end
  end

  describe "persisted setting (no stubs)" do
    after { Setting.clear_cache! }

    it "honours a :not_allowed value stored in the settings table" do
      Setting.user_signup = :not_allowed
      get new_user_registration_path
      expect(response).to redirect_to(new_user_session_path)
    end

    it "honours an :allowed value stored in the settings table" do
      Setting.user_signup = :allowed
      get new_user_registration_path
      expect(response).to have_http_status(:ok)
    end

    it "picks up a setting change without a cache reset between requests" do
      Setting.user_signup = :allowed
      get new_user_registration_path
      expect(response).to have_http_status(:ok)

      Setting.user_signup = :not_allowed
      get new_user_registration_path
      expect(response).to redirect_to(new_user_session_path)
    end
  end

  context "when user_signup is :not_allowed" do
    before { allow(Setting).to receive(:user_signup).and_return(:not_allowed) }

    it "redirects invalid sign up params without rendering validation errors" do
      expect { post user_registration_path, params: { user: { username: "", email: "bad" } } }.not_to change(User, :count)
      expect(response).to redirect_to(new_user_session_path)
      expect(response.body).not_to include("prohibited this User from being saved")
    end

    it "does not send a confirmation email" do
      expect { post user_registration_path, params: params }.not_to change(ActionMailer::Base.deliveries, :size)
    end

    it "redirects the legacy /signup route through to sign in" do
      get "/signup"
      expect(response).to redirect_to("/users/sign_up")
      follow_redirect!
      expect(response).to redirect_to(new_user_session_path)
    end

    it "shows the alert on the sign in page after the redirect" do
      get new_user_registration_path
      follow_redirect!
      expect(response).to have_http_status(:ok)
      expect(response.body).to include(not_allowed_message)
    end

    it "falls back to the English message when the current locale lacks msg_signup_not_allowed" do
      original = I18n.t(:msg_signup_not_allowed, locale: :"en-US")
      I18n.backend.store_translations(:"en-US", msg_signup_not_allowed: nil)
      begin
        expect(I18n.t(:msg_signup_not_allowed, default: nil)).to be_nil
        get new_user_registration_path
        expect(flash[:alert]).to eq(not_allowed_message)
      ensure
        I18n.backend.store_translations(:"en-US", msg_signup_not_allowed: original)
      end
      expect(I18n.t(:msg_signup_not_allowed, locale: :"en-US")).to eq(original)
    end

    it "does not alter the sign in page itself" do
      get new_user_session_path
      expect(response).to have_http_status(:ok)
      expect(response.body).not_to include(new_user_registration_path)
    end

    it "still allows admins to create users through the admin area" do
      admin = create(:user, admin: true)
      admin.confirm
      admin.update_attribute(:suspended_at, nil)
      login_as(admin, scope: :user)

      expect do
        post admin_users_path, params: params, xhr: true
      end.to change(User, :count).by(1)
      expect(User.find_by(username: "newuser")).not_to be_suspended
    ensure
      Warden.test_reset!
    end
  end

  context "when user_signup is :allowed" do
    before { allow(Setting).to receive(:user_signup).and_return(:allowed) }

    it "renders validation errors for invalid sign up params" do
      expect { post user_registration_path, params: { user: { username: "", email: "bad" } } }.not_to change(User, :count)
      expect(response.body).to include("prohibited this User from being saved")
    end

    it "sends a confirmation email and redirects to sign in" do
      expect { post user_registration_path, params: params }.to change(ActionMailer::Base.deliveries, :size).by(1)
      expect(response).to redirect_to(new_user_session_path)
      expect(User.find_by(username: "newuser")).not_to be_suspended
    end
  end

  context "when user_signup is :needs_approval" do
    before { allow(Setting).to receive(:user_signup).and_return(:needs_approval) }

    it "renders the sign up page" do
      get new_user_registration_path
      expect(response).to have_http_status(:ok)
    end
  end

  describe "signed-in users" do
    let(:user) { create(:user) }

    before do
      user.confirm
      user.update_attribute(:suspended_at, nil)
      login_as(user, scope: :user)
    end

    after { Warden.test_reset! }

    %i[allowed not_allowed].each do |value|
      context "when user_signup is #{value.inspect}" do
        before { allow(Setting).to receive(:user_signup).and_return(value) }

        it "still redirects GET /users/edit to the profile page" do
          get edit_user_registration_path
          expect(response).to redirect_to(profile_path)
        end

        it "redirects GET /users/sign_up away without a signup alert" do
          get new_user_registration_path
          expect(response).to redirect_to(root_path)
          expect(flash[:alert]).not_to eq(not_allowed_message)
        end
      end
    end
  end
end
