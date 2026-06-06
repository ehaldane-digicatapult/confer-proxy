package org.moxie.confer.proxy.services;

import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientRequest;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.RuntimeUnknownHostException;
import io.helidon.webclient.api.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;

/**
 * Fetches a web page's raw HTML through an external pass-through proxy, on behalf
 * of a web client. A browser cannot fetch a cross-origin merchant page directly
 * (CORS), so the web app asks the proxy to fetch it; the proxy routes through
 * a third party rather than hitting the merchant directly, so Confer's own network
 * never sees the user's target. The third party never sees the user IP, and Confer's
 * network never sees the target.
 */
@ApplicationScoped
public class ExternalProxyService {

  private static final Logger log = LoggerFactory.getLogger(ExternalProxyService.class);

  private static final String CORSFIX_URL = "https://proxy.corsfix.com/";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration READ_TIMEOUT    = Duration.ofSeconds(12);

  private final Config config;

  private final WebClient client = WebClient.builder()
      .baseUri(CORSFIX_URL)
      .connectTimeout(CONNECT_TIMEOUT)
      .readTimeout(READ_TIMEOUT)
      .followRedirects(true)
      .build();

  @Inject
  public ExternalProxyService(Config config) {
    this.config = config;
  }

  /**
   * GET the page through external proxy for visibility separation, returning its raw HTML,
   * or null on any failure. Retries once on a 5xx (Corsfix surfaces transient upstream/proxy errors as
   * 5xx); a 4xx is the merchant blocking or not-found and is not retried.
   */
  public String fetchHtml(String url, Map<String, String> headers) {
    HttpClientRequest request = client.get().queryParam("url", url);
    applyBrowserHeaders(request, headers);
    request.header(HeaderNames.create("x-corsfix-key"), config.getCorsfixApiKey());

    try (HttpClientResponse response = request.request()) {
      int status = response.status().code();
      if (status < 200 || status >= 300) {
        log.warn("Corsfix returned status {}", status);
        return null;
      }
      return response.as(String.class);
    } catch (UncheckedIOException | RuntimeUnknownHostException e) {
      log.warn("Corsfix request failed");
      return null;
    }
  }

  /** Apply the client-specified browser headers to the outgoing request. */
  private static void applyBrowserHeaders(HttpClientRequest request, Map<String, String> headers) {
    if (headers == null) {
      return;
    }

    for (Map.Entry<String, String> header : headers.entrySet()) {
      if (header.getKey() == null || header.getValue() == null) {
        continue;
      }

      request.header(HeaderNames.create(header.getKey()), header.getValue());
    }
  }
}
