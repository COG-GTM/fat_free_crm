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
      expect(response).to render_template("lists/create")
    end

    it "should not update an existing personal list with a javascript: url" do
      @list = List.create!(name: list_name, url: "/test", user_id: current_user.id)
      post :create, params: { list: { name: list_name, url: "javascript:alert(document.cookie)" }, is_global: is_global }, xhr: true
      expect(assigns(:list).id).to eql(@list.id)
      expect(@list.reload.url).to eql("/test")
      expect(List.where(name: list_name).count).to eql(1)
    end
  end

  describe "rejected urls" do
    let(:list_name) { "Rejected list item" }

    ["//evil.example.com/", "/\\evil.example.com/", "http://evil.example.com/", "data:text/html,<script>alert(1)</script>", "leads"].each do |bad_url|
      it "should not save a list with url #{bad_url.inspect}" do
        post :create, params: { list: { name: list_name, url: bad_url }, is_global: "1" }, xhr: true
        expect(assigns(:list).persisted?).to eql(false)
        expect(List.where(name: list_name)).to be_empty
        expect(response).to render_template("lists/create")
      end
    end

    it "should keep the rejected url on the unsaved list so the form can be re-rendered" do
      post :create, params: { list: { name: list_name, url: "javascript:alert(1)" }, is_global: "1" }, xhr: true
      expect(response).to have_http_status(:ok)
      expect(assigns(:list)).to be_new_record
      expect(assigns(:list).errors[:url]).to eql([I18n.t("errors.messages.invalid")])
      expect(response).to render_template("lists/create")
    end

    it "should return validation errors as JSON with 422 status" do
      post :create, params: { list: { name: list_name, url: "javascript:alert(1)" }, is_global: "1" }, format: :json
      expect(response).to have_http_status(:unprocessable_content)
      errors = ActiveSupport::JSON.decode(response.body)
      expect(errors).to eql("errors" => { "url" => [I18n.t("errors.messages.invalid")] })
      expect(List.where(name: list_name)).to be_empty
    end

    it "should still accept a relative url" do
      post :create, params: { list: { name: list_name, url: list_url }, is_global: "1" }, xhr: true
      expect(assigns(:list).persisted?).to eql(true)
      expect(List.find_by(name: list_name).url).to eql(list_url)
    end
  end

  describe "when not logged in" do
    before(:each) do
      sign_out :user
    end

    it "should not create a list and should redirect to the sign in page" do
      post :create, params: { list: { name: "Anonymous list", url: "/leads" }, is_global: "1" }
      expect(response).to redirect_to(new_user_session_path)
      expect(List.where(name: "Anonymous list")).to be_empty
    end

    it "should not destroy a list" do
      @list = List.create!(name: "Anonymous list", url: "/leads")
      delete :destroy, params: { id: @list.id }
      expect(response).to redirect_to(new_user_session_path)
      expect(List.find(@list.id)).to eql(@list)
    end
  end
end
