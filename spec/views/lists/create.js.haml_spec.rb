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

  describe "with a valid url" do
    before do
      @list = create(:list, name: "Hot leads", url: "/leads?q%5Bfirst_name_cont%5D=Hot", user: current_user)
      assign(:list, @list)
    end

    it "should replace the lists sidebar and show the saved list link" do
      render

      expect(rendered).to include("$('#lists').replaceWith(")
      expect(rendered).to include("$('.list_save').show();")
      expect(rendered).to include("Hot leads")
      expect(rendered).to include("/leads?q%5Bfirst_name_cont%5D=Hot")
      expect(rendered).not_to include("[name='list[name]']\").focus()")
    end
  end

  describe "with an invalid url" do
    before do
      @list = build(:list, name: "Evil list", url: "javascript:alert(document.cookie)")
      @list.valid?
      assign(:list, @list)
    end

    it "should re-enable the form instead of rendering the list" do
      render

      expect(rendered).to include("$form.find(\"[name='list[name]']\").focus();")
      expect(rendered).to include("$form.find(\"[type=submit]\").prop('disabled', false);")
      expect(rendered).not_to include("replaceWith")
      expect(rendered).not_to include("javascript:alert")
    end
  end
end
