# frozen_string_literal: true

require 'spec_helper'

RSpec.describe AccountWebsiteJob do
  let(:account) { create(:account, website: 'http://example.com', phone: nil, email: nil, fax: nil) }
  let(:html_body) do
    <<-HTML
      <html>
        <body>
          <script type="application/ld+json">
            {
              "@context": "https://schema.org",
              "@type": "Organization",
              "telephone": "123-456-7890",
              "email": "info@example.com",
              "faxNumber": "098-765-4321",
              "geo": {
                "@type": "GeoCoordinates",
                "latitude": "40.7128",
                "longitude": "-74.0060"
              },
              "address": {
                "@type": "PostalAddress",
                "streetAddress": "123 Main St",
                "addressLocality": "New York",
                "addressRegion": "NY",
                "postalCode": "10001",
                "addressCountry": "US"
              }
            }
          </script>
        </body>
      </html>
    HTML
  end

  let(:fetcher) { instance_double(SafeHttpFetcher, fetch: html_body) }

  before do
    allow(SafeHttpFetcher).to receive(:new).and_return(fetcher)
  end

  it 'updates account fields from JSON-LD Organization data' do
    expect do
      AccountWebsiteJob.perform_now(account)
    end.to change { account.reload.phone }.to('123-456-7890')
                                          .and change(account, :email).to('info@example.com')
                                          .and change(account, :fax).to('098-765-4321')
                                          .and change(account, :latitude).to(40.7128)
                                          .and change(account, :longitude).to(-74.0060)
  end

  it 'updates account billing address from JSON-LD' do
    AccountWebsiteJob.perform_now(account)
    account.reload
    address = account.billing_address
    expect(address.street1).to eq('123 Main St')
    expect(address.city).to eq('New York')
    expect(address.state).to eq('NY')
    expect(address.zipcode).to eq('10001')
    expect(address.country).to eq('US')
  end

  it 'updates account social media fields from JSON-LD sameAs array' do
    html = <<-HTML
      <html>
        <body>
          <script type="application/ld+json">
            {
              "@context": "https://schema.org",
              "@type": "Organization",
              "sameAs": [
                "https://www.facebook.com/example",
                "https://www.instagram.com/example",
                "https://twitter.com/example",
                "https://www.linkedin.com/company/example",
                "https://bsky.app/profile/example.bsky.social",
                "https://mastodon.social/@example"
              ]
            }
          </script>
        </body>
      </html>
    HTML
    allow(fetcher).to receive(:fetch).and_return(html)

    expect do
      AccountWebsiteJob.perform_now(account)
    end.to change { account.reload.facebook }.to('https://www.facebook.com/example')
                                             .and change(account, :instagram).to('https://www.instagram.com/example')
                                             .and change(account, :twitter).to('https://twitter.com/example')
                                             .and change(account, :linkedin).to('https://www.linkedin.com/company/example')
                                             .and change(account, :bluesky).to('https://bsky.app/profile/example.bsky.social')
                                             .and change(account, :mastodon).to('https://mastodon.social/@example')
  end

  it 'updates account social media fields from JSON-LD sameAs string' do
    html = <<-HTML
      <html>
        <body>
          <script type="application/ld+json">
            {
              "@context": "https://schema.org",
              "@type": "Organization",
              "sameAs": "https://www.facebook.com/example"
            }
          </script>
        </body>
      </html>
    HTML
    allow(fetcher).to receive(:fetch).and_return(html)

    expect do
      AccountWebsiteJob.perform_now(account)
    end.to change { account.reload.facebook }.to('https://www.facebook.com/example')
  end

  it 'does not overwrite existing fields' do
    account.update(phone: '555-5555')
    expect do
      AccountWebsiteJob.perform_now(account)
    end.not_to(change { account.reload.phone })
  end

  describe 'Error handling and safety' do
    before do
      allow(SafeHttpFetcher).to receive(:new).and_call_original
      allow(Net::HTTP).to receive(:new).and_call_original
    end

    it 'handles fetch failures gracefully' do
      allow(fetcher).to receive(:fetch).and_return(nil)

      expect do
        AccountWebsiteJob.perform_now(account)
      end.not_to raise_error
    end

    it 'rejects internal IP addresses (SSRF mitigation)' do
      account.update(website: 'http://192.168.1.1')
      expect(Net::HTTP).not_to receive(:new)

      expect { AccountWebsiteJob.perform_now(account) }.not_to raise_error
    end

    it 'rejects localhost (SSRF mitigation)' do
      allow(Resolv).to receive(:getaddresses).with('localhost').and_return(['127.0.0.1'])
      account.update(website: 'http://localhost')
      expect(Net::HTTP).not_to receive(:new)

      expect { AccountWebsiteJob.perform_now(account) }.not_to raise_error
    end

    it 'rejects the cloud metadata endpoint (SSRF mitigation)' do
      account.update(website: 'http://169.254.169.254/latest/meta-data/')
      expect(Net::HTTP).not_to receive(:new)

      expect { AccountWebsiteJob.perform_now(account) }.not_to raise_error
    end

    it 'rejects hostnames that resolve to internal addresses (SSRF mitigation)' do
      allow(Resolv).to receive(:getaddresses).with('internal.example.com').and_return(['10.0.0.5'])
      account.update(website: 'http://internal.example.com')
      expect(Net::HTTP).not_to receive(:new)

      expect { AccountWebsiteJob.perform_now(account) }.not_to raise_error
    end
  end
end

RSpec.describe 'Account Callback', type: :model do
  include ActiveJob::TestHelper

  before do
    ActiveJob::Base.queue_adapter = :test
  end

  after do
    ActiveJob::Base.queue_adapter = :solid_queue
  end

  it 'enqueues AccountWebsiteJob when website is changed' do
    account = create(:account, website: nil)
    expect do
      account.update(website: 'http://new-website.com')
    end.to enqueue_job(AccountWebsiteJob).with(account)
  end

  it 'does not enqueue AccountWebsiteJob when website is not changed' do
    account = create(:account, website: 'http://old-website.com')
    expect do
      account.update(name: 'New Name')
    end.not_to enqueue_job(AccountWebsiteJob)
  end
end
