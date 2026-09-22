# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
class RegistrationsController < Devise::RegistrationsController
  respond_to :html
  append_view_path 'app/views/devise'

  before_action :ensure_signup_allowed, only: %i[new create] # rubocop:disable Rails/LexicallyScopedActionFilter

  def edit
    redirect_to profile_path
  end

  def after_inactive_sign_up_path_for(*)
    new_user_session_path
  end

  private

  def ensure_signup_allowed
    redirect_to new_user_session_path unless User.can_signup?
  end
end
