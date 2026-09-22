# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'spec_helper'

RSpec.describe RegistrationsController do
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
end
