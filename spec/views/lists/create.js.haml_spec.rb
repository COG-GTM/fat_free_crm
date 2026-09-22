# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'spec_helper'

describe "lists/create" do
  before do
    controller.controller_path = 'lists'
    login
  end

  describe "create success" do
    before do
      @list = create(:list, name: "Hot leads", url: "/leads?q%5Bfirst_name_cont%5D=Hot", user_id: current_user.id)
      assign(:list, @list)
    end

    it "should replace the lists panel with a link to the relative url" do
      render

      expect(rendered).to include("$('#lists').replaceWith(")
      expect(rendered).to include("$('.list_save').show();")
      expect(rendered).to include("Hot leads")
      expect(rendered).to include("/leads?q%5Bfirst_name_cont%5D=Hot")
    end
  end

  describe "create failure" do
    before do
      assign(:list, build(:list, name: "Evil list", url: "javascript:alert(document.cookie)"))
    end

    it "should re-enable the form and never render the rejected url" do
      render

      expect(rendered).to include("$form.find(\"[name='list[name]']\").focus();")
      expect(rendered).to include("$form.find(\"[type=submit]\").prop('disabled', false);")
      expect(rendered).not_to include("replaceWith")
      expect(rendered).not_to include("javascript:alert")
    end
  end
end
