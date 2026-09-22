# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'spec_helper'

describe List do
  it "should parse the controller from the url" do
    ["/controller/action", "controller/action?utf8=%E2%9C%93"].each do |url|
      list = build(:list, url: url)
      expect(list.controller).to eq("controller")
    end
    list = build(:list, url: nil)
    expect(list.controller).to eq(nil)
  end

  describe "url validation" do
    it "should accept relative application paths" do
      ["/leads", "/contacts?q%5Bs%5D%5B0%5D%5Bname%5D=&page=1", "/accounts/1"].each do |url|
        expect(build(:list, url: url)).to be_valid
      end
    end

    it "should reject urls with a scheme or protocol-relative prefix" do
      [
        "javascript:alert(document.cookie)",
        "JavaScript:alert(1)",
        " javascript:alert(1)",
        "data:text/html,<script>alert(1)</script>",
        "http://evil.example.com/",
        "//evil.example.com/",
        "/\\evil.example.com/",
        "/\t/evil.example.com/",
        "/\n/evil.example.com/",
        "/\r\n/evil.example.com/",
        "/leads?q=1\njavascript:alert(1)",
        "leads"
      ].each do |url|
        list = build(:list, url: url)
        expect(list).not_to be_valid, "expected #{url.inspect} to be rejected"
        expect(list.errors[:url]).not_to be_empty
      end
    end
  end
end
