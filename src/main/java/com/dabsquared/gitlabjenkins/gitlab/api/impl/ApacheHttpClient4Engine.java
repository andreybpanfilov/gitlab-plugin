package com.dabsquared.gitlabjenkins.gitlab.api.impl;

import org.apache.http.HttpHost;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.Configurable;
import org.apache.http.protocol.HttpContext;

public class ApacheHttpClient4Engine extends org.jboss.resteasy.client.jaxrs.engines.ApacheHttpClient4Engine {


    public ApacheHttpClient4Engine() {
        super();
    }

    public ApacheHttpClient4Engine(HttpClient httpClient) {
        super(httpClient);
    }

    public ApacheHttpClient4Engine(HttpClient httpClient, boolean closeHttpClient) {
        super(httpClient, closeHttpClient);
    }

    public ApacheHttpClient4Engine(HttpClient httpClient, HttpContext httpContext) {
        super(httpClient, httpContext);
    }

    @Override
    public HttpHost getDefaultProxy() {
        Configurable clientConfiguration = (Configurable) httpClient;
        return clientConfiguration.getConfig().getProxy();
    }

    @Override
    public void setDefaultProxy(HttpHost defaultProxy) {
        throw new UnsupportedOperationException();
    }

}
