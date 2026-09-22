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

    it "should reject paths that are not anchored at the application root" do
      [" /leads", "\n/leads", "\t/leads", "\\leads", "\\/leads", "?q=1", "#leads", ".", "./leads", "../leads", "%2Fleads", "%6Aavascript:alert(1)"].each do |url|
        list = build(:list, url: url)
        expect(list).not_to be_valid, "expected #{url.inspect} to be rejected"
        expect(list.errors[:url]).to include(I18n.t("errors.messages.invalid"))
      end
    end

    it "should accept the root path and paths with fragments, encoded characters or a trailing slash" do
      ["/", "/leads/", "/leads#top", "/leads?q%5Bs%5D=first_name+asc&distinct=1", "/leads/1/edit"].each do |url|
        expect(build(:list, url: url)).to be_valid, "expected #{url.inspect} to be accepted"
      end
    end

    it "should report the format error with the standard invalid message" do
      list = build(:list, url: "javascript:alert(1)")
      list.valid?
      expect(list.errors[:url]).to eq([I18n.t("errors.messages.invalid")])
      expect(list.errors[:name]).to be_empty
    end

    it "should only report the presence error when url is blank" do
      [nil, "", "   "].each do |url|
        list = build(:list, url: url)
        expect(list).not_to be_valid
        expect(list.errors[:url]).to eq([I18n.t("errors.messages.blank")]), "expected only the blank error for #{url.inspect}"
      end
    end

    it "should apply to both global and personal lists" do
      expect(build(:list, url: "javascript:alert(1)", user: nil)).not_to be_valid
      expect(build(:list, url: "javascript:alert(1)", user: create(:user))).not_to be_valid
      expect(build(:list, url: "/leads", user: nil)).to be_valid
      expect(build(:list, url: "/leads", user: create(:user))).to be_valid
    end

    it "should not change an existing list when updating it with a rejected url" do
      list = create(:list, url: "/leads?page=1")
      expect(list.update(url: "javascript:alert(1)")).to eq(false)
      expect(list.errors[:url]).not_to be_empty
      expect(list.reload.url).to eq("/leads?page=1")
      expect { list.update!(url: "//evil.example.com/") }.to raise_error(ActiveRecord::RecordInvalid)
      expect(list.reload.url).to eq("/leads?page=1")
    end

    it "should persist and reload an encoded relative url unchanged" do
      url = "/contacts?q%5Bs%5D%5B0%5D%5Bname%5D=first_name&q%5Bg%5D%5B0%5D%5Bc%5D%5B0%5D%5Bv%5D%5B0%5D%5Bvalue%5D=%3Cb%3Etest%3C%2Fb%3E&distinct=1&page=1"
      list = create(:list, url: url)
      list = List.find(list.id)
      expect(list.url).to eq(url)
      expect(list.controller).to eq("contacts")
      expect(list).to be_valid
    end

    it "should flag rows that bypassed validation as invalid when reloaded" do
      list = create(:list, url: "/leads")
      list.update_column(:url, "javascript:alert(document.cookie)")
      reloaded = List.find(list.id)
      expect(reloaded.url).to eq("javascript:alert(document.cookie)")
      expect(reloaded).not_to be_valid
      expect(reloaded.errors[:url]).to include(I18n.t("errors.messages.invalid"))
      expect(reloaded.update(name: "renamed")).to eq(false)
    end
  end
end
