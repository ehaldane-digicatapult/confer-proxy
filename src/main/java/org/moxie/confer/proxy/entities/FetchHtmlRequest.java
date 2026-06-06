package org.moxie.confer.proxy.entities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Body of a POST /v1/fetch/html request: the page URL to fetch via external proxy, and
 * the browser-like request headers the client wants presented to the site.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FetchHtmlRequest(String url, Map<String, String> headers) {}
