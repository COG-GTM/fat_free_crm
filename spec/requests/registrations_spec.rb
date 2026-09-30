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
end
