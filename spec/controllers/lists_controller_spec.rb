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

    it "should persist the url and owner exactly as submitted" do
      post :create, params: { list: { name: list_name, url: list_url }, is_global: is_global }, xhr: true
      list = List.find_by(name: list_name)
      expect(list.url).to eql(list_url)
      expect(list.user_id).to eql(current_user.id)
    end

    ["javascript:alert(document.cookie)", "http://evil.example.com/", "//evil.example.com/", "/\t/evil.example.com/"].each do |bad_url|
      it "should not save a personal list with url #{bad_url.inspect} and re-render the form" do
        post :create, params: { list: { name: list_name, url: bad_url }, is_global: is_global }, xhr: true
        expect(assigns(:list).persisted?).to eql(false)
        expect(assigns(:list).errors[:url]).to eql(["is invalid"])
        expect(List.where(name: list_name)).to be_empty
        expect(response).to have_http_status(:ok)
        expect(response).to render_template("lists/create")
      end
    end

    it "should keep the original url when the current user's list is overwritten with a javascript: url" do
      @list = List.create!(name: list_name, url: "/test", user_id: current_user.id)
      post :create, params: { list: { name: list_name, url: "javascript:alert(document.cookie)" }, is_global: is_global }, xhr: true
      expect(assigns(:list)).to eql(@list)
      expect(@list.reload.url).to eql("/test")
      expect(response).to render_template("lists/create")
    end

    it "should not create a second list when the submitted url is rejected" do
      List.create!(name: list_name, url: "/test", user_id: current_user.id)
      expect do
        post :create, params: { list: { name: list_name, url: "javascript:alert(1)" }, is_global: is_global }, xhr: true
      end.not_to change(List, :count)
    end
  end

  describe "when not logged in" do
    before(:each) do
      sign_out :user
    end

    it "should not create a list" do
      expect do
        post :create, params: { list: { name: "Anonymous", url: "/leads" }, is_global: "1" }, xhr: true
      end.not_to change(List, :count)
      expect(response).to have_http_status(:unauthorized)
    end

    it "should not destroy a list" do
      list = List.create!(name: "Global", url: "/leads")
      delete :destroy, params: { id: list.id }, xhr: true
      expect(List.exists?(list.id)).to eql(true)
      expect(response).to have_http_status(:unauthorized)
    end
  end
end
