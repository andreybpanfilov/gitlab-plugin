package com.dabsquared.gitlabjenkins.connection;


import com.cloudbees.plugins.credentials.*;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.dabsquared.gitlabjenkins.gitlab.api.GitLabClient;
import com.dabsquared.gitlabjenkins.gitlab.api.GitLabClientBuilder;
import com.dabsquared.gitlabjenkins.gitlab.api.impl.AutodetectGitLabClientBuilder;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import net.karneim.pojobuilder.GeneratePojoBuilder;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials;
import static com.dabsquared.gitlabjenkins.gitlab.api.GitLabClientBuilder.getGitLabClientBuilderById;


/**
 * @author Robin Müller
 */
public class GitLabConnection {

    private final String name;
    private final String url;
    private transient String apiToken;
    // TODO make final when migration code gets removed
    private String apiTokenId;
    private transient GitLabClientBuilder clientBuilder;
    private final boolean ignoreCertificateErrors;
    private final Integer connectionTimeout;
    private final Integer readTimeout;
    private transient GitLabClient apiCache;
    private final Integer retryInterval;
    private final Integer retryCount;
    private final String retryStatusCodes;

    @DataBoundConstructor
    public GitLabConnection(String name, String url, String apiTokenId, String clientBuilderId, boolean ignoreCertificateErrors, Integer connectionTimeout, Integer readTimeout, Integer retryCount, Integer retryInterval, String retryStatusCodes) {
        this(
            name,
            url,
            apiTokenId,
            getGitLabClientBuilderById(clientBuilderId),
            ignoreCertificateErrors,
            connectionTimeout,
            readTimeout,
            retryCount,
            retryInterval,
            retryStatusCodes
        );
    }

    @Restricted(NoExternalUse.class)
    @GeneratePojoBuilder(withFactoryMethod = "*")
    public GitLabConnection(String name, String url, String apiTokenId, GitLabClientBuilder clientBuilder, boolean ignoreCertificateErrors, Integer connectionTimeout, Integer readTimeout, Integer retryCount, Integer retryInterval, String retryStatusCodes) {
        this.name = name;
        this.url = url;
        this.apiTokenId = apiTokenId;
        this.clientBuilder = clientBuilder;
        this.ignoreCertificateErrors = ignoreCertificateErrors;
        this.connectionTimeout = connectionTimeout == null ? 10 : connectionTimeout;
        this.readTimeout = readTimeout == null ? 10 : readTimeout;
        this.retryCount = retryCount == null ? 0 : retryCount;
        this.retryInterval = retryInterval == null ? 0 : retryInterval;
        this.retryStatusCodes = retryStatusCodes;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getApiTokenId() {
        return apiTokenId;
    }

    public String getClientBuilderId() {
        return clientBuilder.id();
    }

    public boolean isIgnoreCertificateErrors() {
        return ignoreCertificateErrors;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public int getRetryInterval() {
        return retryInterval;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getRetryStatusCodes() {
        return retryStatusCodes;
    }

    public GitLabClient getClient() {
        if (apiCache == null) {
            apiCache = clientBuilder.buildClient(this, getApiToken(apiTokenId));
        }

        return apiCache;
    }

    private String getApiToken(String apiTokenId) {
        StandardCredentials credentials = CredentialsMatchers.firstOrNull(
            lookupCredentials(StandardCredentials.class, (Item) null, ACL.SYSTEM, new ArrayList<DomainRequirement>()),
            CredentialsMatchers.withId(apiTokenId));
        if (credentials != null) {
            if (credentials instanceof GitLabApiToken) {
                return ((GitLabApiToken) credentials).getApiToken().getPlainText();
            }
            if (credentials instanceof StringCredentials) {
                return ((StringCredentials) credentials).getSecret().getPlainText();
            }
        }
        throw new IllegalStateException("No credentials found for credentialsId: " + apiTokenId);
    }


    protected GitLabConnection readResolve() {
        boolean replace = false;
        GitLabClientBuilder clientBuilder = this.clientBuilder;
        if (clientBuilder == null) {
            clientBuilder = new AutodetectGitLabClientBuilder();
            replace = true;
        }
        Integer connectionTimeout = this.connectionTimeout;
        if (connectionTimeout == null) {
            connectionTimeout = 10;
            replace = true;
        }
        Integer readTimeout = this.readTimeout;
        if (readTimeout == null) {
            readTimeout = 10;
            replace = true;
        }
        Integer retryInterval = this.retryInterval;
        if (retryInterval == null) {
            retryInterval = 0;
            replace = true;
        }
        Integer retryCount = this.retryCount;
        if (retryCount == null) {
            retryCount = 0;
            replace = true;
        }

        if (!replace) {
            return this;
        }

        return new GitLabConnection(name, url, apiTokenId, clientBuilder, ignoreCertificateErrors, connectionTimeout, readTimeout, retryCount, retryInterval, retryStatusCodes);
    }

    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void migrate() throws IOException {
        GitLabConnectionConfig descriptor = (GitLabConnectionConfig) Jenkins.getInstance().getDescriptor(GitLabConnectionConfig.class);
        for (GitLabConnection connection : descriptor.getConnections()) {
            if (connection.apiTokenId == null && connection.apiToken != null) {
                for (CredentialsStore credentialsStore : CredentialsProvider.lookupStores(Jenkins.getInstance())) {
                    if (credentialsStore instanceof SystemCredentialsProvider.StoreImpl) {
                        List<Domain> domains = credentialsStore.getDomains();
                        connection.apiTokenId = UUID.randomUUID().toString();
                        credentialsStore.addCredentials(domains.get(0),
                            new GitLabApiTokenImpl(CredentialsScope.SYSTEM, connection.apiTokenId, "GitLab API Token", Secret.fromString(connection.apiToken)));
                    }
                }
            }
        }
        descriptor.save();
    }

}
