# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'spec_helper'

describe RegistrationsController do
  let(:valid_params) do
    { user: { username: "newuser", email: "newuser@example.com",
              password: "password", password_confirmation: "password" } }
  end
  let(:invalid_params) do
    { user: { username: "", email: "", password: "", password_confirmation: "" } }
  end

  before do
    @request.env["devise.mapping"] = Devise.mappings[:user]
    ActionMailer::Base.deliveries.clear
  end

  # GET /users/sign_up
  #----------------------------------------------------------------------------
  describe "responding to GET new" do
    context "when user_signup is :not_allowed" do
      before { allow(Setting).to receive(:user_signup).and_return(:not_allowed) }

      it "should redirect to the sign in page with an alert" do
        get :new
        expect(response).to redirect_to(new_user_session_path)
        expect(flash[:alert]).to eq("User signup is not allowed. Please contact your system administrator.")
        expect(response).not_to render_template("registrations/new")
      end
    end

    context "when user_signup is not configured" do
      before { allow(Setting).to receive(:user_signup).and_return(nil) }

      it "should treat a missing setting as not allowed" do
        get :new
        expect(response).to redirect_to(new_user_session_path)
        expect(flash[:alert]).to eq(I18n.t(:msg_signup_not_allowed))
      end
    end

    context "when user_signup is an unknown value" do
      before { allow(Setting).to receive(:user_signup).and_return(:maybe) }

      it "should treat an unknown setting as not allowed" do
        get :new
        expect(response).to redirect_to(new_user_session_path)
      end
    end

    %i[allowed needs_approval].each do |setting|
      context "when user_signup is :#{setting}" do
        before { allow(Setting).to receive(:user_signup).and_return(setting) }

        it "should render the sign up form" do
          get :new
          expect(response).to have_http_status(:ok)
          expect(response).to render_template("registrations/new")
          expect(flash[:alert]).to be_nil
        end
      end
    end

    context "when already signed in" do
      before do
        allow(Setting).to receive(:user_signup).and_return(:not_allowed)
        login
      end

      it "should redirect to the root page (Devise require_no_authentication) rather than the sign in page" do
        get :new
        expect(response).to redirect_to(root_path)
        expect(flash[:alert]).to eq(I18n.t("devise.failure.already_authenticated"))
      end
    end
  end

  # POST /users
  #----------------------------------------------------------------------------
  describe "responding to POST create" do
    context "when user_signup is :not_allowed" do
      before { allow(Setting).to receive(:user_signup).and_return(:not_allowed) }

      it "should not create a user and should redirect to the sign in page" do
        expect { post :create, params: valid_params }.not_to change(User, :count)
        expect(response).to redirect_to(new_user_session_path)
        expect(flash[:alert]).to eq(I18n.t(:msg_signup_not_allowed))
      end

      it "should not send a confirmation email" do
        post :create, params: valid_params
        expect(ActionMailer::Base.deliveries).to be_empty
      end

      it "should not sign the visitor in" do
        post :create, params: valid_params
        expect(controller.current_user).to be_nil
        expect(controller).not_to be_user_signed_in
      end

      it "should reject before running validations on invalid params" do
        expect { post :create, params: invalid_params }.not_to change(User, :count)
        expect(response).to redirect_to(new_user_session_path)
        expect(response).not_to render_template("registrations/new")
      end

      it "should not create a user even when signup params include admin" do
        expect do
          post :create, params: { user: valid_params[:user].merge(admin: true) }
        end.not_to change(User, :count)
        expect(response).to redirect_to(new_user_session_path)
      end
    end

    context "when user_signup is :allowed" do
      before { allow(Setting).to receive(:user_signup).and_return(:allowed) }

      it "should create an unconfirmed, unsuspended user and redirect to the sign in page" do
        expect { post :create, params: valid_params }.to change(User, :count).by(1)
        user = User.find_by(username: "newuser")
        expect(user).not_to be_confirmed
        expect(user).not_to be_suspended
        expect(user.admin).to be(false)
        expect(response).to redirect_to(new_user_session_path)
        expect(flash[:alert]).to be_nil
      end

      it "should send a confirmation email" do
        post :create, params: valid_params
        expect(ActionMailer::Base.deliveries.size).to eq(1)
        expect(ActionMailer::Base.deliveries.last.to).to eq(["newuser@example.com"])
      end

      it "should re-render the form with errors on invalid params" do
        expect { post :create, params: invalid_params }.not_to change(User, :count)
        expect(response).to have_http_status(:ok)
        expect(response).to render_template("registrations/new")
        expect(assigns[:user].errors).not_to be_empty
      end

      it "should not create a user with a duplicate username" do
        create(:user, username: "newuser")
        expect { post :create, params: valid_params }.not_to change(User, :count)
        expect(response).to render_template("registrations/new")
        expect(assigns[:user].errors[:username]).not_to be_empty
      end
    end

    context "when user_signup is :needs_approval" do
      before { allow(Setting).to receive(:user_signup).and_return(:needs_approval) }

      it "should create a suspended user awaiting approval and redirect to the sign in page" do
        expect { post :create, params: valid_params }.to change(User, :count).by(1)
        user = User.find_by(username: "newuser")
        expect(user).to be_suspended
        expect(user).to be_awaits_approval
        expect(response).to redirect_to(new_user_session_path)
        expect(flash[:alert]).to be_nil
      end
    end
  end

  # Setting persistence (no stubbing): the controller must honour the value
  # stored in the settings table and pick up changes on the next request.
  #----------------------------------------------------------------------------
  describe "reading user_signup from the settings table" do
    before { Setting.clear_cache! }

    after do
      Setting.where(name: "user_signup").delete_all
      Setting.clear_cache!
    end

    it "should default to :not_allowed from config/settings.default.yml" do
      expect(Setting.user_signup).to eq(:not_allowed)
      get :new
      expect(response).to redirect_to(new_user_session_path)
    end

    it "should block signup when :not_allowed is persisted" do
      Setting.user_signup = :not_allowed
      expect { post :create, params: valid_params }.not_to change(User, :count)
      expect(response).to redirect_to(new_user_session_path)
    end

    it "should allow signup when :allowed is persisted" do
      Setting.user_signup = :allowed
      get :new
      expect(response).to have_http_status(:ok)
      expect { post :create, params: valid_params }.to change(User, :count).by(1)
    end

    it "should pick up a setting change on the next request without restart" do
      Setting.user_signup = :allowed
      get :new
      expect(response).to have_http_status(:ok)

      Setting.user_signup = :not_allowed
      get :new
      expect(response).to redirect_to(new_user_session_path)
    end
  end

  # Actions outside the gate must not be affected.
  #----------------------------------------------------------------------------
  describe "actions not gated by user_signup" do
    before { allow(Setting).to receive(:user_signup).and_return(:not_allowed) }

    it "should still redirect GET edit to the profile page for a signed in user" do
      login
      get :edit
      expect(response).to redirect_to(profile_path)
    end

    it "should still require authentication for GET edit" do
      get :edit
      expect(response).to redirect_to(new_user_session_path)
      expect(flash[:alert]).not_to eq(I18n.t(:msg_signup_not_allowed))
    end
  end
end
