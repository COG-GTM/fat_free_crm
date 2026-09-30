# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../../spec_helper')

describe "contacts/_sidebar_show" do
  include ContactsHelper

  before do
    login
    assign(:users, [current_user])
    assign(:comment, Comment.new)
    assign(:contact, create(:contact,
                            blog: 'javascript:alert(document.domain)//\nhttp://x',
                            linkedin: 'data:text/html,<script>alert(1)</script>',
                            facebook: '//evil.example.com',
                            twitter: 'twitter.com/account',
                            zoom: 'https://zoom.us/j/1',
                            teams: nil, signal: nil, instagram: nil, mastodon: nil, bluesky: nil))
  end

  it "should render only safe http(s) web presence links" do
    render
    expect(rendered).to have_tag("span.web-presence-icons")
    expect(rendered).to have_tag("a[href='http://twitter.com/account'][data-popup]")
    expect(rendered).to have_tag("a[href='https://zoom.us/j/1'][data-popup]")
    expect(rendered).to have_tag("span.web-presence-icons a[data-popup]", count: 2)
    expect(rendered).not_to include("javascript:")
    expect(rendered).not_to include("data:text/html")
    expect(rendered).not_to include("evil.example.com")
    expect(rendered).not_to have_tag("a[href^='javascript']")
  end
end
