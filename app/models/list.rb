# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
class List < ActiveRecord::Base
  # Only relative application paths are allowed ("/leads?..."). Anything with
  # a scheme (javascript:, http:) or a protocol-relative prefix (//, /\) is
  # rejected since the url is rendered as a link for every user.
  RELATIVE_PATH_FORMAT = %r{\A/(?![/\\]).*\z}m

  validates_presence_of :name
  validates_presence_of :url
  validates_format_of :url, with: RELATIVE_PATH_FORMAT, allow_blank: true
  belongs_to :user, optional: true

  # Parses the controller from the url
  def controller
    (url || "").sub(%r{\A/}, '').split(%r{/|\?}).first
  end

  ActiveSupport.run_load_hooks(:fat_free_crm_list, self)
end
