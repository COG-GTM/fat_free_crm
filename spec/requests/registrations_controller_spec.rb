# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'spec_helper'

RSpec.describe RegistrationsController do
  around do |example|
    original_store = Rack::Attack.cache.store
    Rack::Attack.cache.store = ActiveSupport::Cache::MemoryStore.new
    example.run
  ensure
    Rack::Attack.cache.store = original_store
  end

  let(:params) do
    { user: { username: "newuser", email: "newuser@example.com", password: "password1", password_confirmation: "password1" } }
  end

  context "when user signup is not allowed" do
    before { allow(Setting).to receive(:user_signup).and_return(:not_allowed) }

    it "does not create a user on POST /users" do
      expect { post user_registration_path, params: params }.not_to change(User, :count)
      expect(response).to redirect_to(new_user_session_path)
    end

    it "redirects GET /users/sign_up to the sign in page" do
      get new_user_registration_path
      expect(response).to redirect_to(new_user_session_path)
    end

    it "redirects POST /users before validating the submitted attributes" do
      invalid_params = { user: params[:user].merge(password_confirmation: "mismatch") }

      expect { post user_registration_path, params: invalid_params }.not_to change(User, :count)
      expect(response).to redirect_to(new_user_session_path)
    end

    it "does not set a flash message when redirecting" do
      get new_user_registration_path
      expect(flash).to be_empty
    end

    it "still redirects GET /users/edit to the profile page for a signed in user" do
      login_as(create(:user), scope: :user)

      get edit_user_registration_path
      expect(response).to redirect_to(profile_path)
    end
  end

  context "when the signup setting is not one of the known values" do
    [nil, :unknown].each do |value|
      context "with #{value.inspect}" do
        before { allow(Setting).to receive(:user_signup).and_return(value) }

        it "treats signup as disabled" do
          get new_user_registration_path
          expect(response).to redirect_to(new_user_session_path)

          expect { post user_registration_path, params: params }.not_to change(User, :count)
          expect(response).to redirect_to(new_user_session_path)
        end
      end
    end
  end

  context "when user signup needs approval" do
    before { allow(Setting).to receive(:user_signup).and_return(:needs_approval) }

    it "renders the sign up page" do
      get new_user_registration_path
      expect(response).to have_http_status(:ok)
    end

    it "creates a suspended user awaiting approval on POST /users" do
      expect { post user_registration_path, params: params }.to change(User, :count).by(1)

      user = User.find_by(username: "newuser")
      expect(user).to be_suspended
      expect(user).to be_awaits_approval
      expect(user).not_to be_active_for_authentication
      expect(response).to redirect_to(new_user_session_path)
    end
  end

  context "when user signup is allowed" do
    before { allow(Setting).to receive(:user_signup).and_return(:allowed) }

    it "creates a user on POST /users" do
      expect { post user_registration_path, params: params }.to change(User, :count).by(1)
    end

    it "renders the sign up page" do
      get new_user_registration_path
      expect(response).to have_http_status(:ok)
    end

    it "persists the submitted attributes without suspending the user" do
      post user_registration_path, params: params

      user = User.find_by(username: "newuser")
      expect(user.email).to eq("newuser@example.com")
      expect(user).not_to be_suspended
      expect(user).not_to be_confirmed
      expect(user.valid_password?("password1")).to be(true)
      expect(response).to redirect_to(new_user_session_path)
    end

    it "does not create a user when the submitted attributes are invalid" do
      invalid_params = { user: params[:user].merge(password_confirmation: "mismatch") }

      expect { post user_registration_path, params: invalid_params }.not_to change(User, :count)
      expect(response).not_to be_redirect
      expect(response.body).to include("Password confirmation doesn&#39;t match Password")
    end

    it "does not create a duplicate user when the username is already taken" do
      create(:user, username: "newuser")

      expect { post user_registration_path, params: params }.not_to change(User, :count)
      expect(response).not_to be_redirect
    end
  end
end
