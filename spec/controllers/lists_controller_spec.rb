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
      expect(assigns(:list).errors[:url]).not_to be_empty
      expect(List.where(name: list_name)).to be_empty
    end
    it "should not update an existing personal list with a javascript: url" do
      @list = List.create!(name: list_name, url: "/test", user_id: current_user.id)
      post :create, params: { list: { name: list_name, url: "javascript:alert(document.cookie)" }, is_global: is_global }, xhr: true
      expect(assigns(:list).id).to eql(@list.id)
      expect(assigns(:list).errors[:url]).not_to be_empty
      expect(@list.reload.url).to eql("/test")
    end
  end

  describe "rejected urls" do
    render_views

    let(:list_name) { "Rejected list item" }

    ["//evil.example.com/", "http://evil.example.com/", "data:text/html,<script>alert(1)</script>", "leads"].each do |bad_url|
      it "should not save a list with the url #{bad_url.inspect}" do
        post :create, params: { list: { name: list_name, url: bad_url }, is_global: "1" }, xhr: true
        expect(assigns(:list).persisted?).to eql(false)
        expect(List.where(name: list_name)).to be_empty
      end
    end

    it "should render the create template so the client can re-enable the form" do
      post :create, params: { list: { name: list_name, url: "javascript:alert(1)" }, is_global: "1" }, xhr: true
      expect(response).to be_successful
      expect(response).to render_template("lists/create")
      expect(response.body).to include("[type=submit]")
      expect(response.body).not_to include("javascript:alert(1)")
    end

    it "should not save a list with a blank url" do
      post :create, params: { list: { name: list_name, url: "" }, is_global: "1" }, xhr: true
      expect(assigns(:list).persisted?).to eql(false)
      expect(List.where(name: list_name)).to be_empty
    end
  end

  describe "when not logged in" do
    before(:each) do
      sign_out :user
    end

    it "should not create a list" do
      post :create, params: { list: { name: "Anonymous list", url: list_url }, is_global: "1" }, xhr: true
      expect(response).to have_http_status(:unauthorized)
      expect(List.where(name: "Anonymous list")).to be_empty
    end
  end
end
