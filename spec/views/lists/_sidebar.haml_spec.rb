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

  it "should render saved lists as links to their stored relative url" do
    list = build_stubbed(:list, name: "Hot leads", url: "/leads?q%5Bfirst_name_cont%5D=Hot&page=1")

    render partial: "lists/sidebar", locals: { lists: [list], personal: true }

    expect(rendered).to have_tag("a[href='/leads?q%5Bfirst_name_cont%5D=Hot&page=1'][title='Hot leads']", text: "Hot leads")
    expect(rendered).to have_tag("i.fa[data-controller='leads']")
    expect(rendered).to have_tag("input[type='hidden'][name='is_global'][value='0']")
  end

  it "should render the save form with an empty hidden url field" do
    render partial: "lists/sidebar", locals: { lists: [] }

    expect(rendered).to have_tag("form.new_list")
    expect(rendered).to have_tag("input[type='hidden'][name='list[url]']")
    expect(rendered).not_to have_tag("input[type='hidden'][name='list[url]'][value]")
    expect(rendered).to have_tag("input[type='hidden'][name='is_global'][value='1']")
    expect(rendered).to have_text(I18n.t(:no_saved_lists))
  end
end
