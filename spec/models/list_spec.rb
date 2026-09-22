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

    it "should accept boundary relative paths" do
      [
        "/",
        "/leads#top",
        "/leads?q%5Bfirst_name_cont%5D=caf\u00e9",
        "/javascript:alert(1)",
        "/leads?redirect=http://evil.example.com/"
      ].each do |url|
        expect(build(:list, url: url)).to be_valid, "expected #{url.inspect} to be accepted"
      end
    end

    it "should reject paths containing whitespace or control characters anywhere" do
      [
        "/leads ",
        "/leads\t",
        "/leads x",
        "/leads\0",
        "/leads\u00a0",
        "/leads\u2028",
        "/\u3000",
        "\\leads"
      ].each do |url|
        list = build(:list, url: url)
        expect(list).not_to be_valid, "expected #{url.inspect} to be rejected"
        expect(list.errors[:url]).to eq(["is invalid"])
      end
    end

    it "should report a format error for a javascript: url" do
      list = build(:list, url: "javascript:alert(document.cookie)")
      expect(list).not_to be_valid
      expect(list.errors[:url]).to eq(["is invalid"])
      expect(list.errors[:name]).to be_empty
    end

    it "should only report a presence error for a blank url" do
      [nil, "", "   "].each do |url|
        list = build(:list, url: url)
        expect(list).not_to be_valid
        expect(list.errors[:url]).to eq(["can't be blank"])
      end
    end

    it "should anchor the format so the regexp cannot match a partial line" do
      expect(List::RELATIVE_PATH_FORMAT.source).to start_with('\A')
      expect(List::RELATIVE_PATH_FORMAT.source).to end_with('\z')
      expect(List::RELATIVE_PATH_FORMAT).not_to match("javascript:alert(1)\n/leads")
      expect(List::RELATIVE_PATH_FORMAT).not_to match("/leads\njavascript:alert(1)")
    end
  end

  describe "persistence" do
    let(:xss_url) { "javascript:alert(document.cookie)" }

    it "should not persist a list with a non-relative url" do
      expect { create(:list, url: xss_url) }.to raise_error(ActiveRecord::RecordInvalid, /Url is invalid/)
      expect(List.count).to eq(0)
    end

    it "should round-trip a relative url with an encoded query string" do
      url = "/contacts?q%5Bs%5D%5B0%5D%5Bname%5D=first_name&q%5Bs%5D%5B0%5D%5Bdir%5D=asc&page=1"
      list = create(:list, url: url)
      expect(List.find(list.id).url).to eq(url)
      expect(List.find(list.id).controller).to eq("contacts")
    end

    it "should keep the stored url when an update to a non-relative url fails" do
      list = create(:list, url: "/leads")
      expect(list.update(url: xss_url)).to eq(false)
      expect(list.errors[:url]).to eq(["is invalid"])
      expect(list.reload.url).to eq("/leads")
    end

    it "should flag a legacy record with a non-relative url as invalid and allow it to be repaired" do
      list = create(:list, url: "/leads")
      list.update_column(:url, xss_url)

      legacy = List.find(list.id)
      expect(legacy).not_to be_valid
      expect(legacy.errors[:url]).to eq(["is invalid"])
      expect(legacy.update(name: "Renamed")).to eq(false)

      expect(legacy.update(url: "/leads?q%5Bstatus_eq%5D=new")).to eq(true)
      expect(legacy.reload.url).to eq("/leads?q%5Bstatus_eq%5D=new")
    end

    it "should still parse the controller from a persisted url" do
      list = create(:list, url: "/opportunities?q%5Bstage_eq%5D=won")
      expect(list.reload.controller).to eq("opportunities")
    end
  end
end
