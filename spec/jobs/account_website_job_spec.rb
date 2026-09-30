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

  describe 'fetcher wiring' do
    it 'fetches through SafeHttpFetcher using the stored website value' do
      AccountWebsiteJob.perform_now(account)

      expect(SafeHttpFetcher).to have_received(:new).with('http://example.com')
      expect(fetcher).to have_received(:fetch)
    end

    it 'skips accounts without a website' do
      blank_account = create(:account, website: nil)

      AccountWebsiteJob.perform_now(blank_account)

      expect(SafeHttpFetcher).not_to have_received(:new)
    end

    it 'does nothing when the fetched body is empty' do
      allow(fetcher).to receive(:fetch).and_return('')

      expect { AccountWebsiteJob.perform_now(account) }.not_to(change { account.reload.attributes })
      expect(account.addresses).to be_empty
    end

    it 'does nothing when the fetched body has no JSON-LD' do
      allow(fetcher).to receive(:fetch).and_return('<html><body><p>Hello</p></body></html>')

      expect { AccountWebsiteJob.perform_now(account) }.not_to(change { account.reload.attributes })
      expect(account.addresses).to be_empty
    end

    it 'does not swallow unexpected fetcher errors' do
      allow(fetcher).to receive(:fetch).and_raise(RuntimeError, 'boom')

      expect { AccountWebsiteJob.perform_now(account) }.to raise_error(RuntimeError, 'boom')
    end
  end

  describe 'disallowed website URLs' do
    before do
      allow(SafeHttpFetcher).to receive(:new).and_call_original
      allow(Net::HTTP).to receive(:new).and_call_original
      allow(Rails.logger).to receive(:warn)
    end

    it 'logs a warning identifying the account and the reason' do
      account.update(website: 'http://10.0.0.1/')

      AccountWebsiteJob.perform_now(account)

      expect(Rails.logger).to have_received(:warn).with(a_string_including("account #{account.id}").and(a_string_including('disallowed address 10.0.0.1')))
    end

    it 'leaves the account and its addresses untouched' do
      account.update(website: 'http://169.254.169.254/')

      expect { AccountWebsiteJob.perform_now(account) }.not_to(change { account.reload.attributes })
      expect(account.addresses).to be_empty
    end

    it 'rejects non-http schemes' do
      account.update(website: 'file:///etc/passwd')
      expect(Net::HTTP).not_to receive(:new)

      expect { AccountWebsiteJob.perform_now(account) }.not_to raise_error
      expect(Rails.logger).to have_received(:warn).with(a_string_including('scheme not allowed'))
    end

    it 'rejects websites with embedded credentials' do
      account.update(website: 'http://admin:secret@example.com/')
      expect(Net::HTTP).not_to receive(:new)

      expect { AccountWebsiteJob.perform_now(account) }.not_to raise_error
      expect(Rails.logger).to have_received(:warn).with(a_string_including('credentials'))
    end

    it 'rejects websites redirecting to internal addresses' do
      allow(Resolv).to receive(:getaddresses).with('example.com').and_return(['93.184.216.34'])
      redirect = Net::HTTPFound.new('1.1', '302', nil)
      redirect['Location'] = 'http://127.0.0.1/admin'
      http = instance_double(Net::HTTP)
      allow(Net::HTTP).to receive(:new).and_return(http)
      allow(http).to receive_messages(:ipaddr= => nil, :use_ssl= => nil, :open_timeout= => nil, :read_timeout= => nil, :max_retries= => nil)
      allow(http).to receive(:start).and_yield(http)
      allow(http).to receive(:request) { |_req, &block| block.call(redirect) }

      expect { AccountWebsiteJob.perform_now(account) }.not_to(change { account.reload.attributes })
      expect(Net::HTTP).to have_received(:new).once
      expect(Rails.logger).to have_received(:warn).with(a_string_including("account #{account.id}").and(a_string_including('127.0.0.1')))
    end
  end

  describe 'end-to-end through SafeHttpFetcher' do
    let(:http) { instance_double(Net::HTTP) }

    before do
      allow(SafeHttpFetcher).to receive(:new).and_call_original
      allow(Resolv).to receive(:getaddresses).with('example.com').and_return(['93.184.216.34'])
      response = Net::HTTPOK.new('1.1', '200', nil)
      allow(response).to receive(:read_body).and_yield(html_body)
      allow(Net::HTTP).to receive(:new).and_return(http)
      allow(http).to receive_messages(:ipaddr= => nil, :use_ssl= => nil, :open_timeout= => nil, :read_timeout= => nil, :max_retries= => nil)
      allow(http).to receive(:start).and_yield(http)
      allow(http).to receive(:request) { |_req, &block| block.call(response) }
    end

    it 'updates the account from JSON-LD fetched over a pinned connection' do
      expect { AccountWebsiteJob.perform_now(account) }.to change { account.reload.phone }.to('123-456-7890')
      expect(Net::HTTP).to have_received(:new).with('example.com', 80, nil)
      expect(http).to have_received(:ipaddr=).with('93.184.216.34')
      expect(account.billing_address.city).to eq('New York')
    end

    it 'still handles websites stored without a scheme' do
      account.update(website: 'example.com')

      expect { AccountWebsiteJob.perform_now(account) }.to change { account.reload.phone }.to('123-456-7890')
      expect(Net::HTTP).to have_received(:new).with('example.com', 80, nil)
    end

    it 'leaves the account untouched when the site returns an error' do
      error = Net::HTTPInternalServerError.new('1.1', '500', nil)
      allow(http).to receive(:request) { |_req, &block| block.call(error) }

      expect { AccountWebsiteJob.perform_now(account) }.not_to(change { account.reload.attributes })
      expect(account.addresses).to be_empty
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
