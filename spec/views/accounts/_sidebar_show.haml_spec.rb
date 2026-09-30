# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../../spec_helper')

describe "accounts/_sidebar_show" do
  include AccountsHelper

  before do
    login
    assign(:users, [current_user])
    assign(:comment, Comment.new)
    assign(:account, @account = create(:account,
                                       blog: "javascript:alert(1)",
                                       linkedin: "linkedin.com/company/ffcrm",
                                       twitter: "vbscript:msgbox(1)",
                                       facebook: ""))
  end

  it "should not render web presence links with unsafe URLs" do
    render
    expect(rendered).not_to include("javascript:")
    expect(rendered).not_to include("vbscript:")
    expect(rendered).to have_tag("a[href='http://linkedin.com/company/ffcrm'][data-popup='true']")
    expect(rendered).to have_tag("span.web-presence-icons a", count: 1)
  end
end
