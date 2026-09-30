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
    assign(:account, create(:account,
                            website: 'www.example.com',
                            blog: 'javascript:alert(document.domain)//\nhttp://x',
                            linkedin: 'www.linkedin.com/company/ffcrm',
                            facebook: 'JAVASCRIPT:alert(1)',
                            twitter: 'https://twitter.com/ffcrm',
                            instagram: nil, mastodon: nil, bluesky: nil))
  end

  it "should render only safe http(s) web presence links" do
    render
    expect(rendered).to have_tag("span.web-presence-icons")
    expect(rendered).to have_tag("a[href='http://www.linkedin.com/company/ffcrm'][data-popup]")
    expect(rendered).to have_tag("a[href='https://twitter.com/ffcrm'][data-popup]")
    expect(rendered).to have_tag("span.web-presence-icons a[data-popup]", count: 2)
    expect(rendered).not_to include("javascript:")
    expect(rendered).not_to have_tag("a[href^='javascript']")
  end
end
