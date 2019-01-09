package com.dabsquared.gitlabjenkins.gitlab.api.impl;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.ServiceUnavailableRetryStrategy;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.*;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.jboss.resteasy.client.jaxrs.ClientHttpEngine;
import org.jboss.resteasy.client.jaxrs.engines.PassthroughTrustManager;
import org.jboss.resteasy.client.jaxrs.i18n.Messages;

import javax.net.ssl.*;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ResteasyClientBuilder extends org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder {

    private CredentialsProvider proxyCredentials;

    private int retryCount;

    private int retryInterval;

    private final List<Integer> retryStatusCodes = new ArrayList<>();

    public ResteasyClientBuilder setProxyCredentials(CredentialsProvider proxyCredentials) {
        this.proxyCredentials = proxyCredentials;
        return this;
    }

    public ResteasyClientBuilder setRetryCount(int retryCount) {
        this.retryCount = retryCount;
        return this;
    }

    public ResteasyClientBuilder setRetryInterval(int retryInterval) {
        this.retryInterval = retryInterval;
        return this;
    }

    public ResteasyClientBuilder addRetryStatusCode(int code) {
        this.retryStatusCodes.add(code);
        return this;
    }

    public ResteasyClientBuilder addRetryStatusCodes(Collection<Integer> codes) {
        if (codes != null) {
            this.retryStatusCodes.addAll(codes);
        }
        return this;
    }

    @Override
    protected ClientHttpEngine initDefaultEngine() {
        RequestConfig.Builder configBuilder = RequestConfig.custom();
        if (socketTimeout > -1) {
            configBuilder.setSocketTimeout((int) socketTimeoutUnits.toMillis(socketTimeout));
        }
        if (establishConnectionTimeout > -1) {
            configBuilder.setConnectTimeout((int) establishConnectionTimeoutUnits.toMillis(establishConnectionTimeout));
        }
        if (connectionCheckoutTimeoutMs > -1) {
            configBuilder.setConnectionRequestTimeout(connectionCheckoutTimeoutMs);
        }


        SSLContext context = buildSSLContext();

        HttpClientBuilder httpClientBuilder = HttpClientBuilder.create()
            .setDefaultRequestConfig(configBuilder.build())
            .setProxy(defaultProxy)
            .setConnectionManager(buildConnectionManager(context));

        if (retryCount > 0) {
            List<Integer> statusCodes = new ArrayList<>(this.retryStatusCodes);
            if (statusCodes.isEmpty()) {
                statusCodes.add(HttpStatus.SC_SERVICE_UNAVAILABLE);
            }
            httpClientBuilder.setRetryHandler(new RetryHandler(retryCount, retryInterval));
            httpClientBuilder.setServiceUnavailableRetryStrategy(new RetryStrategy(retryCount, retryInterval, retryStatusCodes));
        }

        if (proxyCredentials != null) {
            httpClientBuilder.setDefaultCredentialsProvider(proxyCredentials);
        }

        ApacheHttpClient4Engine engine = new ApacheHttpClient4Engine(httpClientBuilder.build(), true);
        engine.setResponseBufferSize(responseBufferSize);
        engine.setHostnameVerifier(verifier);
        engine.setSslContext(context);
        return engine;

    }

    private X509HostnameVerifier buildHostnameVerifier() {
        if (this.verifier != null) {
            return new VerifierWrapper(this.verifier);
        }
        if (disableTrustManager) {
            return new AllowAllHostnameVerifier();
        }
        switch (policy) {
            case ANY:
                return new AllowAllHostnameVerifier();
            case WILDCARD:
                return new BrowserCompatHostnameVerifier();
            case STRICT:
                return new StrictHostnameVerifier();
            default:
                return new BrowserCompatHostnameVerifier();
        }

    }

    private SSLContext buildSSLContext() {
        try {
            if (disableTrustManager) {
                SSLContext context = SSLContext.getInstance("SSL");
                context.init(null, new TrustManager[]{new PassthroughTrustManager()},
                    new SecureRandom());
                return context;
            }
            if (sslContext != null) {
                return sslContext;
            }
            if (clientKeyStore != null || truststore != null) {
                return SSLContexts.custom()
                    .useProtocol(SSLConnectionSocketFactory.TLS)
                    .setSecureRandom(null)
                    .loadKeyMaterial(clientKeyStore, clientPrivateKeyPassword != null ? clientPrivateKeyPassword.toCharArray() : null)
                    .loadTrustMaterial(truststore)
                    .build();
            }
            SSLContext context = SSLContext.getInstance(SSLConnectionSocketFactory.TLS);
            context.init(null, null, null);
            return context;
        } catch (GeneralSecurityException ex) {
            throw new RuntimeException(ex);
        }
    }

    protected HttpClientConnectionManager buildConnectionManager(SSLContext context) {
        Registry<ConnectionSocketFactory> registry = getConnectionSocketFactoryRegistry(context);
        if (connectionPoolSize <= 0) {
            return new BasicHttpClientConnectionManager(registry);
        }
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager(registry, null, null, null, connectionTTL, connectionTTLUnit);
        cm.setMaxTotal(connectionPoolSize);
        if (maxPooledPerRoute == 0) {
            cm.setDefaultMaxPerRoute(connectionPoolSize);
        } else {
            cm.setDefaultMaxPerRoute(maxPooledPerRoute);
        }
        return cm;

    }

    private Registry<ConnectionSocketFactory> getConnectionSocketFactoryRegistry(SSLContext context) {
        SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(context, buildHostnameVerifier());
        return RegistryBuilder.<ConnectionSocketFactory>create()
            .register("http", PlainConnectionSocketFactory.getSocketFactory())
            .register("https", sslsf)
            .build();
    }

    static class VerifierWrapper implements X509HostnameVerifier {

        protected HostnameVerifier verifier;

        VerifierWrapper(HostnameVerifier verifier) {
            this.verifier = verifier;
        }

        @Override
        public void verify(String host, SSLSocket ssl) throws IOException {
            if (!verifier.verify(host, ssl.getSession()))
                throw new SSLException(Messages.MESSAGES.hostnameVerificationFailure());
        }

        @Override
        public void verify(String host, X509Certificate cert) throws SSLException {
            throw new SSLException(Messages.MESSAGES.verificationPathNotImplemented());
        }

        @Override
        public void verify(String host, String[] cns, String[] subjectAlts) throws SSLException {
            throw new SSLException(Messages.MESSAGES.verificationPathNotImplemented());
        }

        @Override
        public boolean verify(String s, SSLSession sslSession) {
            return verifier.verify(s, sslSession);
        }

    }

    static class RetryHandler extends DefaultHttpRequestRetryHandler {

        private final int retryInterval;

        protected RetryHandler(int retryCount, int retryInterval) {
            super(retryCount, false);
            this.retryInterval = retryInterval;
        }

        @Override
        public boolean retryRequest(IOException exception, int executionCount, HttpContext context) {
            boolean result = super.retryRequest(exception, executionCount, context);
            if (!result || retryInterval <= 0) {
                return result;
            }
            try {
                Thread.sleep(1000L * retryInterval);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return true;
        }

    }

    static class RetryStrategy implements ServiceUnavailableRetryStrategy {

        private final int retryCount;

        private final int retryInterval;

        private final List<Integer> restryStatusCodes;

        public RetryStrategy(int retryCount, int retryInterval, List<Integer> restryStatusCodes) {
            this.retryCount = retryCount;
            this.retryInterval = retryInterval;
            this.restryStatusCodes = restryStatusCodes;
        }

        @Override
        public boolean retryRequest(HttpResponse response, int executionCount, HttpContext context) {
            if (executionCount > retryCount) {
                return false;
            }
            return restryStatusCodes.contains(response.getStatusLine().getStatusCode());
        }

        @Override
        public long getRetryInterval() {
            return 1000L * retryInterval;
        }

    }

}
