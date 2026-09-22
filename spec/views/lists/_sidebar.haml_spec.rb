# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'spec_helper'

describe "lists/_sidebar" do
  before do
    login
  end

  it "should link each saved list to its relative url" do
    list = create(:list, name: "Hot leads", url: "/leads?q%5Bfirst_name_cont%5D=Hot", user: current_user)
    render partial: "lists/sidebar", locals: { lists: List.where(user_id: current_user.id), personal: true }

    expect(rendered).to have_tag("a[href='/leads?q%5Bfirst_name_cont%5D=Hot']", text: "Hot leads")
    expect(rendered).to have_tag("a[href='#{list_path(list)}'][data-method='delete']")
  end

  it "should html-escape the url when it is rendered as a link" do
    create(:list, name: "Escaped", url: "/leads?q=<script>alert(1)</script>&a=\"b\"", user: current_user)
    render partial: "lists/sidebar", locals: { lists: List.where(user_id: current_user.id), personal: true }

    expect(rendered).to include("href=\"/leads?q=&lt;script&gt;alert(1)&lt;/script&gt;&amp;a=&quot;b&quot;\"")
    expect(rendered).not_to include("<script>alert(1)</script>")
  end

  it "should show a placeholder when there are no lists" do
    render partial: "lists/sidebar", locals: { lists: List.none }

    expect(rendered).to have_text("No saved lists")
    expect(rendered).not_to have_tag("li")
  end
end
