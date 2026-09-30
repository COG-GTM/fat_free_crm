# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
class RegistrationsController < Devise::RegistrationsController
  respond_to :html
  append_view_path 'app/views/devise'

  before_action :require_signup_allowed, only: %i[new create]

  def new
    super
  end

  def create
    super
  end

  def edit
    redirect_to profile_path
  end

  def after_inactive_sign_up_path_for(*)
    new_user_session_path
  end

  private

  def require_signup_allowed
    return if User.can_signup?

    redirect_to new_user_session_path, alert: t(:msg_signup_not_allowed)
  end
end
