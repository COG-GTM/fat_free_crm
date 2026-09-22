# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'spec_helper'

describe ListsController do
  before(:each) do
    login
  end

  let(:list_url) { "/contacts?q%5Bs%5D%5B0%5D%5Bname%5D=&q%5Bs%5D%5B0%5D%5Bdir%5D=asc&q%5Bg%5D%5B0%5D%5Bm%5D=and&q%5Bg%5D%5B0%5D%5Bc%5D%5B0%5D%5Ba%5D%5B0%5D%5Bname%5D=first_name&q%5Bg%5D%5B0%5D%5Bc%5D%5B0%5D%5Bp%5D=cont&q%5Bg%5D%5B0%5D%5Bc%5D%5B0%5D%5Bv%5D%5B0%5D%5Bvalue%5D=test&distinct=1&page=1" }

  describe "global list items" do
    let(:list_name) { "Global list item" }
    let(:is_global) { "1" }
    it "creating should be successful" do
      post :create, params: { list: { name: list_name, url: list_url }, is_global: is_global }, xhr: true
      expect(assigns(:list).persisted?).to eql(true)
      expect(response).to render_template("lists/create")
    end
    it "updating should be successful" do
      @list = List.create!(name: list_name, url: "/test")
      post :create, params: { list: { name: list_name, url: list_url }, is_global: is_global }, xhr: true
      expect(assigns(:list).persisted?).to eql(true)
      expect(@list.reload.url).to eql(list_url)
      expect(response).to render_template("lists/create")
    end
    it "delete list item" do
      @list = List.create!(name: list_name, url: "/test")
      delete :destroy, params: { id: @list.id }, xhr: true
      expect { List.find(@list.id) }.to raise_error(ActiveRecord::RecordNotFound)
      expect(response).to render_template("lists/destroy")
    end
    it "should not save a list with a javascript: url" do
      post :create, params: { list: { name: list_name, url: "javascript:alert(document.cookie)" }, is_global: is_global }, xhr: true
      expect(assigns(:list).persisted?).to eql(false)
      expect(List.where(name: list_name)).to be_empty
    end
    it "should not update an existing list with a javascript: url" do
      @list = List.create!(name: list_name, url: "/test")
      post :create, params: { list: { name: list_name, url: "javascript:alert(document.cookie)" }, is_global: is_global }, xhr: true
      expect(@list.reload.url).to eql("/test")
    end
  end

  describe "personal list items" do
    let(:list_name) { "Personal list item" }
    let(:is_global) { "0" }

    it "creating should be successful" do
      post :create, params: { list: { name: list_name, url: list_url }, is_global: is_global }, xhr: true
      expect(assigns(:list).persisted?).to eql(true)
      expect(response).to render_template("lists/create")
    end

    it "should not save a personal list with a javascript: url" do
      post :create, params: { list: { name: list_name, url: "javascript:alert(document.cookie)" }, is_global: is_global }, xhr: true
      expect(assigns(:list).persisted?).to eql(false)
      expect(assigns(:list).errors[:url]).to eql(["is invalid"])
      expect(List.where(name: list_name)).to be_empty
    end

    it "should not overwrite an existing personal list with a javascript: url" do
      @list = List.create!(name: list_name, url: "/test", user_id: current_user.id)
      post :create, params: { list: { name: list_name, url: "javascript:alert(document.cookie)" }, is_global: is_global }, xhr: true
      expect(assigns(:list)).to eql(@list)
      expect(@list.reload.url).to eql("/test")
      expect(List.where(name: list_name).count).to eql(1)
    end

    it "should not touch another user's personal list of the same name" do
      other = create(:user)
      other_list = List.create!(name: list_name, url: "/test", user_id: other.id)
      post :create, params: { list: { name: list_name, url: "javascript:alert(document.cookie)" }, is_global: is_global }, xhr: true
      expect(assigns(:list).persisted?).to eql(false)
      expect(other_list.reload.url).to eql("/test")
      expect(List.where(user_id: current_user.id)).to be_empty
    end
  end

  describe "rejected urls" do
    let(:list_name) { "Rejected list item" }

    [
      "http://evil.example.com/",
      "//evil.example.com/",
      "/\\evil.example.com/",
      "/\t/evil.example.com/",
      "/leads?q=1\njavascript:alert(1)",
      "data:text/html,<script>alert(1)</script>",
      "leads"
    ].each do |bad_url|
      it "should not save a global or personal list with url #{bad_url.inspect}" do
        post :create, params: { list: { name: list_name, url: bad_url }, is_global: "1" }, xhr: true
        expect(assigns(:list).persisted?).to eql(false)
        post :create, params: { list: { name: list_name, url: bad_url }, is_global: "0" }, xhr: true
        expect(assigns(:list).persisted?).to eql(false)
        expect(List.where(name: list_name)).to be_empty
      end
    end

    it "should render the create template with a successful status so the form is re-enabled" do
      post :create, params: { list: { name: list_name, url: "javascript:alert(document.cookie)" }, is_global: "0" }, xhr: true
      expect(response).to be_successful
      expect(response).to render_template("lists/create")
      expect(assigns(:list)).not_to be_valid
    end

    it "should not save a list with a blank url" do
      post :create, params: { list: { name: list_name, url: "" }, is_global: "0" }, xhr: true
      expect(assigns(:list).persisted?).to eql(false)
      expect(assigns(:list).errors[:url]).to eql(["can't be blank"])
      expect(List.where(name: list_name)).to be_empty
    end

    it "should not accept a url that is only whitespace" do
      post :create, params: { list: { name: list_name, url: "   " }, is_global: "0" }, xhr: true
      expect(assigns(:list).persisted?).to eql(false)
      expect(List.where(name: list_name)).to be_empty
    end

    it "should allow a legacy list with a javascript: url to be overwritten with a relative url" do
      @list = List.create!(name: list_name, url: "/test")
      @list.update_column(:url, "javascript:alert(document.cookie)")
      post :create, params: { list: { name: list_name, url: list_url }, is_global: "1" }, xhr: true
      expect(assigns(:list)).to eql(@list)
      expect(@list.reload.url).to eql(list_url)
      expect(assigns(:list)).to be_valid
    end
  end
end
