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

  describe "create success" do
    before do
      assign(:list, create(:list, name: "Hot leads", url: "/leads?q%5Bfirst_name_cont%5D=Hot", user_id: current_user.id))
    end

    it "should replace the lists sidebar with the saved list link" do
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

    it "should keep the form open and re-enable the submit button" do
      render

      expect(rendered).to include("$form = $('.lists .list_form:visible')")
      expect(rendered).to include("$form.find(\"[name='list[name]']\").focus();")
      expect(rendered).to include("$form.find(\"[type=submit]\").prop('disabled', false);")
    end

    it "should not render the rejected url or the lists sidebar" do
      render

      expect(rendered).not_to include("javascript:alert")
      expect(rendered).not_to include("Evil list")
      expect(rendered).not_to include("$('#lists').replaceWith(")
    end
  end
end
