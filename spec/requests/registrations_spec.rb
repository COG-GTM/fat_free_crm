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

    it "creates a suspended user" do
      expect do
        post user_registration_path, params: params
      end.to change(User, :count).by(1)
      expect(User.find_by(username: "newuser")).to be_suspended
    end
  end

  describe "signup guard" do
    before { Rack::Attack.reset! }

    context "when user_signup is not configured" do
      before { allow(Setting).to receive(:user_signup).and_return(nil) }

      it "treats a missing setting as not allowed" do
        get new_user_registration_path
        expect(response).to redirect_to(new_user_session_path)
        expect(flash[:alert]).to eq(I18n.t(:msg_signup_not_allowed))
      end

      it "does not create a user" do
        expect do
          post user_registration_path, params: params
        end.not_to change(User, :count)
      end
    end

    context "when user_signup is a string instead of a symbol" do
      before { allow(Setting).to receive(:user_signup).and_return("allowed") }

      it "treats it as not allowed" do
        get new_user_registration_path
        expect(response).to redirect_to(new_user_session_path)
      end
    end

    context "when user_signup has an unrecognised value" do
      before { allow(Setting).to receive(:user_signup).and_return(:maybe) }

      it "treats it as not allowed" do
        get new_user_registration_path
        expect(response).to redirect_to(new_user_session_path)
      end

      it "does not create a user" do
        expect do
          post user_registration_path, params: params
        end.not_to change(User, :count)
      end
    end

    context "when signup is blocked by :not_allowed" do
      before { allow(Setting).to receive(:user_signup).and_return(:not_allowed) }

      it "does not send a confirmation email" do
        expect do
          post user_registration_path, params: params
        end.not_to change(ActionMailer::Base.deliveries, :size)
      end

      it "sets the alert flash on create" do
        post user_registration_path, params: params
        expect(flash[:alert]).to eq(I18n.t(:msg_signup_not_allowed))
      end

      it "falls back to English when the locale lacks the translation" do
        allow(Setting).to receive(:locale).and_return('de-DE')
        expect(I18n.exists?(:msg_signup_not_allowed, :"de-DE")).to be(false)

        get new_user_registration_path
        expect(response).to redirect_to(new_user_session_path)
        expect(flash[:alert]).to eq("User signup is not allowed. Please contact your system administrator.")
      end

      it "is enforced on the legacy /signup route" do
        get "/signup"
        expect(response).to redirect_to("/users/sign_up")
        follow_redirect!
        expect(response).to redirect_to(new_user_session_path)
      end

      it "shows the alert on the sign in page after redirect" do
        get new_user_registration_path
        follow_redirect!
        expect(response).to have_http_status(:ok)
        expect(response.body).to include("User signup is not allowed")
      end

      it "does not link to sign up from the sign in page" do
        get new_user_session_path
        expect(response.body).not_to include(new_user_registration_path)
        expect(response.body).not_to include(I18n.t(:sign_up_now))
      end

      it "still redirects a signed in user from edit to the profile page" do
        user = create(:user)
        user.confirm
        login_as(user, scope: :user)

        get edit_user_registration_path
        expect(response).to redirect_to(profile_path)
      end
    end

    context "when signup is open via :allowed" do
      before { allow(Setting).to receive(:user_signup).and_return(:allowed) }

      it "links to sign up from the sign in page" do
        get new_user_session_path
        expect(response.body).to include(new_user_registration_path)
      end

      it "creates an unsuspended user awaiting confirmation" do
        post user_registration_path, params: params
        user = User.find_by(username: "newuser")
        expect(user).not_to be_suspended
        expect(user).not_to be_confirmed
        expect(response).to redirect_to(new_user_session_path)
      end

      it "sends a confirmation email" do
        expect do
          post user_registration_path, params: params
        end.to change(ActionMailer::Base.deliveries, :size).by(1)
      end

      it "does not create a user with invalid params" do
        params[:user][:password_confirmation] = "different"
        expect do
          post user_registration_path, params: params
        end.not_to change(User, :count)
        expect(response).not_to be_redirect
      end

      it "does not create a user with a duplicate username" do
        create(:user, username: "newuser")
        expect do
          post user_registration_path, params: params
        end.not_to change(User, :count)
      end

      it "does not create a user with a duplicate email" do
        create(:user, email: "newuser@example.com")
        expect do
          post user_registration_path, params: params
        end.not_to change(User, :count)
      end

      it "does not grant admin through mass assignment" do
        params[:user][:admin] = true
        post user_registration_path, params: params
        expect(User.find_by(username: "newuser")).not_to be_admin
      end

      it "redirects an already signed in user away from sign up" do
        user = create(:user)
        user.confirm
        login_as(user, scope: :user)

        expect do
          post user_registration_path, params: params
        end.not_to change(User, :count)
        expect(response).to be_redirect
        expect(response).not_to redirect_to(new_user_session_path)
      end
    end

    context "when signup requires approval via :needs_approval" do
      before { allow(Setting).to receive(:user_signup).and_return(:needs_approval) }

      it "renders the sign up page" do
        get new_user_registration_path
        expect(response).to have_http_status(:ok)
      end

      it "creates a user that awaits approval and cannot authenticate" do
        post user_registration_path, params: params
        user = User.find_by(username: "newuser")
        expect(user).to be_awaits_approval
        expect(user).not_to be_active_for_authentication
        expect(response).to redirect_to(new_user_session_path)
      end
    end

    context "when the setting is persisted in the database" do
      after { Setting.clear_cache! }

      it "blocks sign up when stored as :not_allowed" do
        Setting.user_signup = :not_allowed
        get new_user_registration_path
        expect(response).to redirect_to(new_user_session_path)
      end

      it "permits sign up when stored as :allowed" do
        Setting.user_signup = :allowed
        get new_user_registration_path
        expect(response).to have_http_status(:ok)
      end
    end
  end
end
