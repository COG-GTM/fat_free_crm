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

  it "should link each saved list to its stored relative url" do
    list = create(:list, name: "Hot leads", url: "/leads?q%5Bs%5D=first_name+asc&distinct=1")

    render partial: "lists/sidebar", locals: { lists: [list] }

    expect(rendered).to have_css("li#list_#{list.id} dt a[href='/leads?q%5Bs%5D=first_name+asc&distinct=1'][title='Hot leads']", text: "Hot leads")
    expect(rendered).to have_css("li#list_#{list.id} a.list_icon[href='/lists/#{list.id}'][data-method='delete'] i[data-controller='leads']")
  end

  it "should show the empty message when there are no saved lists" do
    render partial: "lists/sidebar", locals: { lists: [] }

    expect(rendered).to have_text(I18n.t(:no_saved_lists))
    expect(rendered).to have_no_css("li")
  end
end
