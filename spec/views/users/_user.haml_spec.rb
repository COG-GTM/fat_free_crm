# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../../spec_helper')

describe "users/_user" do
  include UsersHelper

  before do
    login
    assign(:user, @user = create(:user,
                                 blog: "javascript:alert(1)//\nhttp://x",
                                 twitter: "twitter.com/ffcrm",
                                 linkedin: "https://www.linkedin.com/in/ffcrm"))
  end

  it "should not render web presence links with unsafe URLs" do
    render partial: "users/user"
    expect(rendered).not_to include("javascript:")
    expect(rendered).to have_tag("a[href='http://twitter.com/ffcrm'][data-popup='true']")
    expect(rendered).to have_tag("a[href='https://www.linkedin.com/in/ffcrm'][data-popup='true']")
    expect(rendered).to have_tag("span.web-presence-icons a", count: 2)
  end
end
