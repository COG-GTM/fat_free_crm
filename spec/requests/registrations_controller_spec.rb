# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'spec_helper'

RSpec.describe RegistrationsController do
  before do
    Rack::Attack.cache.store = ActiveSupport::Cache::MemoryStore.new
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

  context "when user signup is not allowed and the request is malformed or already authenticated" do
    before { allow(Setting).to receive(:user_signup).and_return(:not_allowed) }

    it "redirects with a 302 and does not render the sign up form" do
      get new_user_registration_path
      expect(response).to have_http_status(:found)
      expect(response.body).not_to include('name="user[username]"')
    end

    it "redirects POST /users without user params instead of raising ParameterMissing" do
      post user_registration_path
      expect(response).to redirect_to(new_user_session_path)
    end

    it "redirects POST /users with invalid params without rendering validation errors" do
      invalid = { user: params[:user].merge(password: "", password_confirmation: "") }
      expect { post user_registration_path, params: invalid }.not_to change(User, :count)
      expect(response).to redirect_to(new_user_session_path)
      expect(response.body).not_to include("prohibited this User from being saved")
    end

    it "does not send a confirmation email on POST /users" do
      expect { post user_registration_path, params: params }.not_to change(ActionMailer::Base.deliveries, :size)
    end

    it "redirects the /signup shortcut through to the sign in page" do
      get "/signup"
      expect(response).to redirect_to("/users/sign_up")
      follow_redirect!
      expect(response).to redirect_to(new_user_session_path)
    end

    context "when a user is already signed in" do
      let(:user) { create(:user) }

      before { login_as(user, scope: :user) }
      after { Warden.test_reset! }

      it "still redirects GET /users/edit to the profile page" do
        get edit_user_registration_path
        expect(response).to redirect_to(profile_path)
      end

      it "redirects GET /users/sign_up away via Devise's already-signed-in check" do
        get new_user_registration_path
        expect(response).to redirect_to(root_path)
      end
    end
  end

  context "when the signup setting has an unrecognised value" do
    before { allow(Setting).to receive(:user_signup).and_return("allowed") }

    it "treats it as not allowed and blocks POST /users" do
      expect { post user_registration_path, params: params }.not_to change(User, :count)
      expect(response).to redirect_to(new_user_session_path)
    end
  end
end
