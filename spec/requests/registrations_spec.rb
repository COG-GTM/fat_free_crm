# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'spec_helper'

RSpec.describe "Registrations" do
  let(:params) do
    { user: { username: "newuser", email: "newuser@example.com",
              password: "password", password_confirmation: "password" } }
  end

  context "when user_signup is :not_allowed" do
    before { allow(Setting).to receive(:user_signup).and_return(:not_allowed) }

    it "does not render the sign up page" do
      get new_user_registration_path
      expect(response).to redirect_to(new_user_session_path)
      expect(flash[:alert]).to eq(I18n.t(:msg_signup_not_allowed))
    end

    it "does not create a user" do
      expect do
        post user_registration_path, params: params
      end.not_to change(User, :count)
      expect(response).to redirect_to(new_user_session_path)
    end
  end

  context "when user_signup is :not_allowed and a user is signed in" do
    let(:user) { create(:user) }

    before do
      allow(Setting).to receive(:user_signup).and_return(:not_allowed)
      user.confirm
      user.update_attribute(:suspended_at, nil)
      login_as(user, scope: :user)
    end

    after { logout(:user) }

    it "does not create a user on POST /users" do
      expect do
        post user_registration_path, params: params
      end.not_to change(User, :count)
      expect(response).to have_http_status(:redirect)
    end

    it "still redirects GET /users/edit to the profile page" do
      get edit_user_registration_path
      expect(response).to redirect_to(profile_path)
    end
  end

  context "when user_signup is :not_allowed and the flash text" do
    before { allow(Setting).to receive(:user_signup).and_return(:not_allowed) }

    it "is set on POST /users" do
      post user_registration_path, params: params
      expect(flash[:alert]).to eq("User signup is not allowed. Please contact your system administrator.")
    end

    it "does not send a confirmation email on POST /users" do
      expect do
        post user_registration_path, params: params
      end.not_to change(ActionMailer::Base.deliveries, :count)
    end

    it "falls back to English when the active locale lacks the translation" do
      allow(Setting).to receive(:locale).and_return('de-DE')
      expect(I18n.exists?(:msg_signup_not_allowed, 'de-DE', fallback: false)).to be(false)

      get new_user_registration_path

      expect(response).to redirect_to(new_user_session_path)
      expect(flash[:alert]).to eq("User signup is not allowed. Please contact your system administrator.")
    end

    it "is enforced on the legacy /signup route" do
      get "/signup"
      expect(response).to redirect_to("/users/sign_up")

      follow_redirect!
      expect(response).to redirect_to(new_user_session_path)
      expect(flash[:alert]).to eq(I18n.t(:msg_signup_not_allowed))
    end
  end

  context "when user_signup is nil" do
    before { allow(Setting).to receive(:user_signup).and_return(nil) }

    it "denies sign up" do
      get new_user_registration_path
      expect(response).to redirect_to(new_user_session_path)
    end

    it "does not create a user" do
      expect do
        post user_registration_path, params: params
      end.not_to change(User, :count)
    end
  end

  context "when user_signup is a string instead of a symbol" do
    before { allow(Setting).to receive(:user_signup).and_return("allowed") }

    it "denies sign up" do
      get new_user_registration_path
      expect(response).to redirect_to(new_user_session_path)
    end
  end

  context "when user_signup is stored in the settings table" do
    after { Setting.clear_cache! }

    it "is re-read on every request" do
      Setting.user_signup = :allowed
      get new_user_registration_path
      expect(response).to have_http_status(:ok)

      Setting.user_signup = :not_allowed
      get new_user_registration_path
      expect(response).to redirect_to(new_user_session_path)

      Setting.user_signup = :needs_approval
      get new_user_registration_path
      expect(response).to have_http_status(:ok)
    end

    it "does not create a user once disabled" do
      Setting.user_signup = :not_allowed
      expect do
        post user_registration_path, params: params
      end.not_to change(User, :count)
    end
  end

  context "when user_signup is :allowed" do
    before { allow(Setting).to receive(:user_signup).and_return(:allowed) }

    it "renders the sign up page" do
      get new_user_registration_path
      expect(response).to have_http_status(:ok)
    end

    it "creates a user" do
      expect do
        post user_registration_path, params: params
      end.to change(User, :count).by(1)
    end
  end

  context "when user_signup is :needs_approval" do
    before { allow(Setting).to receive(:user_signup).and_return(:needs_approval) }

    it "renders the sign up page" do
      get new_user_registration_path
      expect(response).to have_http_status(:ok)
    end

    it "creates a suspended user" do
      expect do
        post user_registration_path, params: params
      end.to change(User, :count).by(1)
      expect(User.find_by(username: "newuser")).to be_suspended
    end
  end
end
