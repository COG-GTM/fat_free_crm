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

    it "should accept the root path and paths with unicode, fragments and percent-encoded characters" do
      [
        "/",
        "/LEADS",
        "/leads#recent",
        "/leads?q=%C3%9Cn%C3%AFcode",
        "/leads?q=Ünïcode",
        "/leads?q=%0Ajavascript%3Aalert%281%29",
        "/contacts?q%5Bs%5D%5B0%5D%5Bname%5D=&q%5Bg%5D%5B0%5D%5Bc%5D%5B0%5D%5Bv%5D%5B0%5D%5Bvalue%5D=a%20b&distinct=1&page=1"
      ].each do |url|
        expect(build(:list, url: url)).to be_valid, "expected #{url.inspect} to be accepted"
      end
    end

    it "should reject unicode whitespace, NUL bytes and a leading backslash" do
      [
        "/leads?q=a\u00a0b",
        "/leads?q=a\u2028b",
        "/\u0000",
        "\\/evil.example.com/",
        "/leads?q=1 ",
        " /leads"
      ].each do |url|
        list = build(:list, url: url)
        expect(list).not_to be_valid, "expected #{url.inspect} to be rejected"
        expect(list.errors[:url]).to eq(["is invalid"])
      end
    end

    it "should only report a presence error for a blank url" do
      [nil, "", "   "].each do |url|
        list = build(:list, url: url)
        expect(list).not_to be_valid
        expect(list.errors[:url]).to eq(["can't be blank"])
      end
    end

    it "should refuse to save an invalid url" do
      list = build(:list, url: "javascript:alert(1)")
      expect(list.save).to eq(false)
      expect(list).to be_new_record
      expect { list.save! }.to raise_error(ActiveRecord::RecordInvalid, /Url is invalid/)
      expect(List.count).to eq(0)
    end

    it "should refuse to change a persisted url to an invalid one" do
      list = create(:list, url: "/leads?q=1")
      expect(list.update(url: "//evil.example.com/")).to eq(false)
      expect(list.reload.url).to eq("/leads?q=1")
    end
  end

  describe "persistence" do
    it "should round-trip a percent-encoded url unchanged" do
      url = "/contacts?q%5Bs%5D%5B0%5D%5Bname%5D=first_name&q%5Bs%5D%5B0%5D%5Bdir%5D=asc&distinct=1&page=1"
      list = create(:list, url: url, user: create(:user))
      reloaded = List.find(list.id)
      expect(reloaded.url).to eq(url)
      expect(reloaded.controller).to eq("contacts")
      expect(reloaded.user).to eq(list.user)
    end

    it "should still allow destroying a list whose stored url predates the format validation" do
      legacy = build(:list, url: "http://legacy.example.com/leads")
      legacy.save!(validate: false)
      expect(legacy).not_to be_valid
      expect(legacy.destroy).to be_destroyed
      expect(List.exists?(legacy.id)).to eq(false)
    end

    it "should not allow renaming a list whose stored url predates the format validation" do
      legacy = build(:list, name: "Legacy", url: "http://legacy.example.com/leads")
      legacy.save!(validate: false)
      expect(legacy.update(name: "Renamed")).to eq(false)
      expect(legacy.errors[:url]).to eq(["is invalid"])
      expect(legacy.reload.name).to eq("Legacy")
    end
  end
end
