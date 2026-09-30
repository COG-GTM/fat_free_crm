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
    assign(:contact, @contact = create(:contact,
                                       blog: "javascript:alert(document.domain)//\nhttp://x",
                                       linkedin: "www.linkedin.com/in/ffcrm",
                                       twitter: "data:text/html,<script>alert(1)</script>",
                                       facebook: "https://facebook.com/ffcrm"))
  end

  it "should not render web presence links with unsafe URLs" do
    render
    expect(rendered).not_to include("javascript:")
    expect(rendered).not_to include("data:text/html")
    expect(rendered).not_to have_tag("a[href^='javascript']")
    expect(rendered).not_to have_tag("a[href^='data']")
    expect(rendered).to have_tag("a[href='http://www.linkedin.com/in/ffcrm']")
    expect(rendered).to have_tag("a[href='https://facebook.com/ffcrm']")
    expect(rendered).to have_tag("a[href='/contacts/#{@contact.id}.vcf'][title='VCard']")
  end
end
