# frozen_string_literal: true

require 'ipaddr'
require 'net/http'
require 'resolv'

# Fetches a user-supplied URL from the server side without exposing internal
# network destinations: only http/https, no private/loopback/link-local/
# metadata addresses (checked after DNS resolution and pinned for the
# connection), bounded timeouts, bounded body size and re-validated redirects.
class SafeHttpFetcher
  class DisallowedUrl < StandardError; end

  ALLOWED_SCHEMES = %w[http https].freeze
  MAX_REDIRECTS = 3
  MAX_BODY_BYTES = 2 * 1024 * 1024
  OPEN_TIMEOUT = 5
  READ_TIMEOUT = 10
  USER_AGENT = 'Fat Free CRM'

  BLOCKED_RANGES = %w[
    0.0.0.0/8
    10.0.0.0/8
    100.64.0.0/10
    127.0.0.0/8
    169.254.0.0/16
    172.16.0.0/12
    192.0.0.0/24
    192.0.2.0/24
    192.88.99.0/24
    192.168.0.0/16
    198.18.0.0/15
    198.51.100.0/24
    203.0.113.0/24
    224.0.0.0/4
    240.0.0.0/4
    ::/128
    ::1/128
    ::ffff:0:0/96
    64:ff9b::/96
    100::/64
    2001::/32
    2001:db8::/32
    fc00::/7
    fe80::/10
    ff00::/8
  ].map { |cidr| IPAddr.new(cidr) }.freeze

  MAX_ADDRESSES_TRIED = 3

  NETWORK_ERRORS = [
    SocketError, IOError, SystemCallError, Timeout::Error,
    Net::HTTPBadResponse, Net::ProtocolError, OpenSSL::SSL::SSLError
  ].freeze

  CONNECT_ERRORS = [Net::OpenTimeout, SystemCallError].freeze

  def initialize(url)
    @url = url.to_s.strip
  end

  # Returns the response body for a 2xx response, or nil on network errors,
  # non-success responses or oversized bodies. Raises DisallowedUrl when the
  # URL (or any redirect target) points at a forbidden destination.
  def fetch
    uri = validate_uri!(parse(@url))

    (MAX_REDIRECTS + 1).times do
      response, body = request_any(uri, pinned_addresses!(uri))

      case response
      when Net::HTTPSuccess
        return body
      when Net::HTTPRedirection
        location = response['location']
        return nil if location.blank?

        uri = validate_uri!(parse(location, base: uri))
      else
        return nil
      end
    end

    nil
  rescue *NETWORK_ERRORS => e
    Rails.logger.warn("SafeHttpFetcher: #{e.class} fetching from #{uri&.host.inspect}: #{e.message}")
    nil
  end

  private

  def parse(url, base: nil)
    url = "http://#{url}" if base.nil? && !url.match?(%r{\A[a-z][a-z0-9+.-]*://}i)
    base ? URI.join(base, url) : URI.parse(url)
  rescue URI::Error, ArgumentError
    raise DisallowedUrl, "invalid URL #{url.inspect}"
  end

  def validate_uri!(uri)
    raise DisallowedUrl, "scheme not allowed: #{uri.scheme.inspect}" unless ALLOWED_SCHEMES.include?(uri.scheme.to_s.downcase)
    raise DisallowedUrl, 'missing host' if uri.host.blank?
    raise DisallowedUrl, 'credentials in URL are not allowed' if uri.userinfo.present?

    uri
  end

  def pinned_addresses!(uri)
    host = uri.hostname
    addresses = literal_ip(host) ? [literal_ip(host)] : resolve(host)
    raise DisallowedUrl, "could not resolve #{host.inspect}" if addresses.empty?

    addresses.each do |ip|
      raise DisallowedUrl, "#{host} resolves to disallowed address #{ip}" if blocked?(ip)
    end

    addresses.first(MAX_ADDRESSES_TRIED).map(&:to_s)
  end

  def literal_ip(host)
    IPAddr.new(host)
  rescue IPAddr::Error
    nil
  end

  def resolve(host)
    Resolv.getaddresses(host).filter_map { |addr| literal_ip(addr) }
  end

  def blocked?(ip)
    ip = ip.native if ip.ipv6? && ip.ipv4_mapped?
    BLOCKED_RANGES.any? { |range| range.include?(ip) }
  end

  # Tries each vetted address in turn, moving on only when the connection
  # itself fails (refused, unreachable, open timeout).
  def request_any(uri, ipaddrs)
    ipaddrs.each_with_index do |ipaddr, index|
      return request(uri, ipaddr)
    rescue *CONNECT_ERRORS
      raise if index == ipaddrs.size - 1
    end
  end

  def request(uri, ipaddr)
    http = Net::HTTP.new(uri.host, uri.port, nil)
    http.ipaddr = ipaddr
    http.use_ssl = uri.scheme.casecmp?('https')
    http.open_timeout = OPEN_TIMEOUT
    http.read_timeout = READ_TIMEOUT
    http.max_retries = 0

    get = Net::HTTP::Get.new(uri)
    get['User-Agent'] = USER_AGENT

    http.start do |conn|
      conn.request(get) do |response|
        return [response, nil] unless response.is_a?(Net::HTTPSuccess)
        return [nil, nil] if response['content-length'].to_i > MAX_BODY_BYTES

        body = +''
        response.read_body do |chunk|
          body << chunk
          return [nil, nil] if body.bytesize > MAX_BODY_BYTES
        end
        return [response, body]
      end
    end
  end
end
