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

    it "should accept the application root and paths with fragments or trailing slashes" do
      ["/", "/leads/", "/leads#top", "/leads?q%5Bname_cont%5D=a%26b"].each do |url|
        expect(build(:list, url: url)).to be_valid, "expected #{url.inspect} to be accepted"
      end
    end

    it "should reject urls that do not start with a slash after leading whitespace or control characters" do
      ["\n/leads", "\t/leads", "\r\n/leads", "\\/leads", "\\\\evil.example.com", "leads/1", "?q=1", "#top"].each do |url|
        list = build(:list, url: url)
        expect(list).not_to be_valid, "expected #{url.inspect} to be rejected"
        expect(list.errors[:url]).to include(I18n.t("errors.messages.invalid"))
      end
    end

    it "should report only a presence error for a blank url" do
      [nil, "", "   "].each do |url|
        list = build(:list, url: url)
        expect(list).not_to be_valid
        expect(list.errors[:url]).to include(I18n.t("errors.messages.blank"))
        expect(list.errors[:url]).not_to include(I18n.t("errors.messages.invalid"))
      end
    end

    it "should still require a name when the url is valid" do
      list = build(:list, name: nil, url: "/leads")
      expect(list).not_to be_valid
      expect(list.errors[:name]).not_to be_empty
      expect(list.errors[:url]).to be_empty
    end
  end

  describe "persistence" do
    let(:relative_url) { "/contacts?q%5Bs%5D%5B0%5D%5Bname%5D=first_name&q%5Bs%5D%5B0%5D%5Bdir%5D=asc&page=1" }

    it "should round-trip a relative url unchanged" do
      list = create(:list, url: relative_url)
      expect(list.reload.url).to eq(relative_url)
      expect(list.controller).to eq("contacts")
    end

    it "should not overwrite a stored url with a non-relative url" do
      list = create(:list, url: relative_url)

      expect(list.update(url: "javascript:alert(document.cookie)")).to eq(false)
      expect(list.errors[:url]).not_to be_empty
      expect(list.reload.url).to eq(relative_url)

      expect { list.update!(url: "//evil.example.com/") }.to raise_error(ActiveRecord::RecordInvalid)
      expect(list.reload.url).to eq(relative_url)
    end

    it "should not create a list with a non-relative url" do
      expect { create(:list, url: "http://evil.example.com/") }.to raise_error(ActiveRecord::RecordInvalid)
      expect(List.count).to eq(0)
    end

    it "should allow changing a stored url to another relative url" do
      list = create(:list, url: relative_url)
      expect(list.update(url: "/leads?q%5Bfirst_name_cont%5D=Hot")).to eq(true)
      expect(list.reload.url).to eq("/leads?q%5Bfirst_name_cont%5D=Hot")
      expect(list.controller).to eq("leads")
    end
  end
end
