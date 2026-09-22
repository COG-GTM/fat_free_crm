# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'spec_helper'

describe "lists/create" do
  before do
    login
  end

  it "should replace the lists sidebar when the list was saved" do
    list = create(:list, name: "Hot leads", url: "/leads?q%5Bs%5D=first_name+asc")
    assign(:list, list)

    render template: 'lists/create', formats: [:js]

    expect(rendered).to include("$('#lists').replaceWith")
    expect(rendered).to include("$('.list_save').show()")
    expect(rendered).to include("Hot leads")
    expect(rendered).to include("/leads?q%5Bs%5D=first_name+asc")
  end

  it "should re-enable the save form and not render the rejected url when validation failed" do
    list = build(:list, name: "Evil list", url: "javascript:alert(document.cookie)")
    list.valid?
    assign(:list, list)

    render template: 'lists/create', formats: [:js]

    expect(rendered).not_to include("replaceWith")
    expect(rendered).not_to include("javascript:alert")
    expect(rendered).to include(%($form.find("[name='list[name]']").focus()))
    expect(rendered).to include(%($form.find("[type=submit]").prop('disabled', false)))
  end
end
