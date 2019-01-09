package com.dabsquared.gitlabjenkins.gitlab.api.impl;


import com.dabsquared.gitlabjenkins.connection.GitLabConnection;
import com.dabsquared.gitlabjenkins.gitlab.JacksonConfig;
import com.dabsquared.gitlabjenkins.gitlab.api.GitLabClient;
import com.dabsquared.gitlabjenkins.gitlab.api.GitLabClientBuilder;
import com.dabsquared.gitlabjenkins.gitlab.api.model.MergeRequest;
import com.dabsquared.gitlabjenkins.util.JsonUtil;
import com.dabsquared.gitlabjenkins.util.LoggerUtil;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import hudson.ProxyConfiguration;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.jboss.resteasy.plugins.providers.JaxrsFormProvider;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.Priority;
import javax.ws.rs.Priorities;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.client.ClientResponseContext;
import javax.ws.rs.client.ClientResponseFilter;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.RuntimeDelegate;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.net.Proxy.Type.HTTP;


@Restricted(NoExternalUse.class)
public class ResteasyGitLabClientBuilder extends GitLabClientBuilder {
    private static final Logger LOGGER = Logger.getLogger(ResteasyGitLabClientBuilder.class.getName());
    private static final String PRIVATE_TOKEN = "PRIVATE-TOKEN";

    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    public static void setRuntimeDelegate() {
        RuntimeDelegate.setInstance(new ResteasyProviderFactory());
    }

    private final Class<? extends GitLabApiProxy> apiProxyClass;
    private final Function<MergeRequest, Integer> mergeRequestIdProvider;

    ResteasyGitLabClientBuilder(String id, int ordinal, Class<? extends GitLabApiProxy> apiProxyClass, Function<MergeRequest, Integer> mergeRequestIdProvider) {
        super(id, ordinal);
        this.apiProxyClass = apiProxyClass;
        this.mergeRequestIdProvider = mergeRequestIdProvider;
    }

    @Nonnull
    @Override
    public final GitLabClient buildClient(GitLabConnection connection, String token) {
        return buildClient(connection, token, Jenkins.getActiveInstance().proxy);
    }

    private GitLabClient buildClient(GitLabConnection connection, String token, ProxyConfiguration httpProxyConfig) {
        ResteasyClientBuilder builder = new ResteasyClientBuilder()
            .setRetryCount(connection.getRetryCount())
            .setRetryInterval(connection.getRetryInterval())
            .addRetryStatusCodes(parseStatusCodes(connection.getRetryStatusCodes()));

        if (connection.isIgnoreCertificateErrors()) {
            builder.hostnameVerification(ResteasyClientBuilder.HostnameVerificationPolicy.ANY);
            builder.disableTrustManager();
        }

        if (httpProxyConfig != null) {
            Proxy proxy = httpProxyConfig.createProxy(getHost(connection.getUrl()));
            if (proxy.type() == HTTP) {
                InetSocketAddress address = (InetSocketAddress) proxy.address();
                String hostname = address.getHostString().replaceFirst("^.*://", "");
                builder.defaultProxy(hostname,
                    address.getPort(),
                    address.getHostName().startsWith("https") ? "https" : "http");
                if (httpProxyConfig.getUserName() != null && httpProxyConfig.getPassword() != null) {
                    CredentialsProvider proxyCredentials = new BasicCredentialsProvider();
                    proxyCredentials.setCredentials(new AuthScope(hostname, address.getPort()),
                        new UsernamePasswordCredentials(httpProxyConfig.getUserName(), httpProxyConfig.getPassword()));
                    builder.setProxyCredentials(proxyCredentials);
                }
            }
        }

        GitLabApiProxy apiProxy = builder
            .connectionPoolSize(60)
            .maxPooledPerRoute(30)
            .establishConnectionTimeout(connection.getConnectionTimeout(), TimeUnit.SECONDS)
            .socketTimeout(connection.getReadTimeout(), TimeUnit.SECONDS)
            .register(new JacksonJsonProvider())
            .register(new JacksonConfig())
            .register(new ApiHeaderTokenFilter(token))
            .register(new LoggingFilter())
            .register(new RemoveAcceptEncodingFilter())
            .register(new JaxrsFormProvider())
            .build().target(connection.getUrl())
            .proxyBuilder(apiProxyClass)
            .classloader(apiProxyClass.getClassLoader())
            .build();

        return new ResteasyGitLabClient(connection.getUrl(), apiProxy, mergeRequestIdProvider);
    }

    private String getHost(String url) {
        try {
            return new URL(url).getHost();
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private static List<Integer> parseStatusCodes(String statusCodes) {
        List<Integer> result = new ArrayList<>();
        if (statusCodes == null) {
            return result;
        }
        for (String part : Splitter.on(",").omitEmptyStrings().trimResults().split(statusCodes)) {
            result.add(Integer.parseInt(part));
        }
        return result;
    }


    @Priority(Priorities.HEADER_DECORATOR)
    private static class ApiHeaderTokenFilter implements ClientRequestFilter {
        private final String gitlabApiToken;

        ApiHeaderTokenFilter(String gitlabApiToken) {
            this.gitlabApiToken = gitlabApiToken;
        }

        public void filter(ClientRequestContext requestContext) {
            requestContext.getHeaders().putSingle(PRIVATE_TOKEN, gitlabApiToken);
        }
    }

    @Priority(Priorities.USER)
    private static class LoggingFilter implements ClientRequestFilter, ClientResponseFilter {
        @Override
        public void filter(ClientRequestContext context) {
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.log(Level.FINEST, "Call GitLab:\nHTTP method: {0}\nURL: {1}\nRequest headers: [\n{2}\n]",
                        LoggerUtil.toArray(context.getMethod(), context.getUri(), toFilteredString(context.getHeaders())));
            }
        }

        @Override
        public void filter(ClientRequestContext request, ClientResponseContext response) {
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.log(Level.FINEST, "Got response from GitLab:\nURL: {0}\nStatus: {1} {2}\nResponse headers: [\n{3}\n]\nResponse body: {4}",
                        LoggerUtil.toArray(request.getUri(), response.getStatus(), response.getStatusInfo(), toString(response.getHeaders()),
                                getPrettyPrintResponseBody(response)));
            }
        }

        private String toFilteredString(MultivaluedMap<String, Object> headers) {
            return FluentIterable.from(headers.entrySet()).transform(new HeaderToFilteredString()).join(Joiner.on(",\n"));
        }

        private String toString(MultivaluedMap<String, String> headers) {
            return FluentIterable.from(headers.entrySet()).transform(new HeaderToString()).join(Joiner.on(",\n"));
        }

        private String getPrettyPrintResponseBody(ClientResponseContext responseContext) {
            String responseBody = getResponseBody(responseContext);
            if (StringUtils.isNotEmpty(responseBody) && responseContext.getMediaType().equals(MediaType.APPLICATION_JSON_TYPE)) {
                return JsonUtil.toPrettyPrint(responseBody);
            }
            return responseBody;
        }

        private String getResponseBody(ClientResponseContext context) {
            try (InputStream entityStream = context.getEntityStream()) {
                if (entityStream != null) {
                    byte[] bytes = IOUtils.toByteArray(entityStream);
                    context.setEntityStream(new ByteArrayInputStream(bytes));
                    return new String(bytes);
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failure during reading the response body", e);
                context.setEntityStream(new ByteArrayInputStream(new byte[0]));
            }
            return "";
        }

        private static class HeaderToFilteredString implements Function<Map.Entry<String, List<Object>>, String> {
            @Nullable
            @Override
            public String apply(@Nullable Map.Entry<String, List<Object>> input) {
                if (input == null) {
                    return null;
                } else if (input.getKey().equals(PRIVATE_TOKEN)) {
                    return input.getKey() + " = [****FILTERED****]";
                } else {
                    return input.getKey() + " = [" + Joiner.on(", ").join(input.getValue()) + "]";
                }
            }
        }

        private static class HeaderToString implements Function<Map.Entry<String, List<String>>, String> {
            @Nullable
            @Override
            public String apply(@Nullable Map.Entry<String, List<String>> input) {
                return input == null ? null : input.getKey() + " = [" + Joiner.on(", ").join(input.getValue()) + "]";
            }
        }
    }

    @Priority(Priorities.HEADER_DECORATOR)
    private static class RemoveAcceptEncodingFilter implements ClientRequestFilter {
        RemoveAcceptEncodingFilter() {}
        @Override
        public void filter(ClientRequestContext clientRequestContext) {
            clientRequestContext.getHeaders().remove("Accept-Encoding");
        }
    }

}
