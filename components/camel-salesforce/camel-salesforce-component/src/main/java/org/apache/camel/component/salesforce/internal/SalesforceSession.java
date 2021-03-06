/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.salesforce.internal;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.camel.Service;
import org.apache.camel.component.salesforce.SalesforceHttpClient;
import org.apache.camel.component.salesforce.SalesforceLoginConfig;
import org.apache.camel.component.salesforce.api.SalesforceException;
import org.apache.camel.component.salesforce.api.dto.RestError;
import org.apache.camel.component.salesforce.api.utils.JsonUtils;
import org.apache.camel.component.salesforce.internal.dto.LoginError;
import org.apache.camel.component.salesforce.internal.dto.LoginToken;
import org.apache.camel.util.ObjectHelper;
import org.eclipse.jetty.client.HttpConversation;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.FormContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.Fields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SalesforceSession implements Service {

    private static final String OAUTH2_REVOKE_PATH = "/services/oauth2/revoke?token=";
    private static final String OAUTH2_TOKEN_PATH = "/services/oauth2/token";

    private static final Logger LOG = LoggerFactory.getLogger(SalesforceSession.class);

    private final SalesforceHttpClient httpClient;
    private final long timeout;

    private final SalesforceLoginConfig config;

    private final ObjectMapper objectMapper;
    private final Set<SalesforceSessionListener> listeners;

    private volatile String accessToken;
    private volatile String instanceUrl;

    public SalesforceSession(SalesforceHttpClient httpClient, long timeout, SalesforceLoginConfig config) {
        // validate parameters
        ObjectHelper.notNull(httpClient, "httpClient");
        ObjectHelper.notNull(config, "SalesforceLoginConfig");
        ObjectHelper.notNull(config.getLoginUrl(), "loginUrl");
        ObjectHelper.notNull(config.getClientId(), "clientId");
        ObjectHelper.notNull(config.getClientSecret(), "clientSecret");
        ObjectHelper.notNull(config.getUserName(), "userName");
        ObjectHelper.notNull(config.getPassword(), "password");

        this.httpClient = httpClient;
        this.timeout = timeout;
        this.config = config;

        // strip trailing '/'
        String loginUrl = config.getLoginUrl();
        config.setLoginUrl(loginUrl.endsWith("/") ? loginUrl.substring(0, loginUrl.length() - 1) : loginUrl);

        this.objectMapper = JsonUtils.createObjectMapper();
        this.listeners = new CopyOnWriteArraySet<SalesforceSessionListener>();
    }

    @SuppressWarnings("unchecked")
    public synchronized String login(String oldToken) throws SalesforceException {

        // check if we need a new session
        // this way there's always a single valid session
        if ((accessToken == null) || accessToken.equals(oldToken)) {

            // try revoking the old access token before creating a new one
            accessToken = oldToken;
            if (accessToken != null) {
                try {
                    logout();
                } catch (SalesforceException e) {
                    LOG.warn("Error revoking old access token: " + e.getMessage(), e);
                }
                accessToken = null;
            }

            // login to Salesforce and get session id
            final Request loginPost = getLoginRequest(null);
            try {

                final ContentResponse loginResponse = loginPost.send();
                parseLoginResponse(loginResponse, loginResponse.getContentAsString());

            } catch (InterruptedException e) {
                throw new SalesforceException("Login error: " + e.getMessage(), e);
            } catch (TimeoutException e) {
                throw new SalesforceException("Login request timeout: " + e.getMessage(), e);
            } catch (ExecutionException e) {
                throw new SalesforceException("Unexpected login error: " + e.getCause().getMessage(), e.getCause());
            }
        }

        return accessToken;
    }

    /**
     * Creates login request, allows SalesforceSecurityHandler to create a login request for a failed authentication conversation
     * @return login POST request.
     */
    public Request getLoginRequest(HttpConversation conversation) {
        final String loginUrl = (instanceUrl == null ? config.getLoginUrl() : instanceUrl) + OAUTH2_TOKEN_PATH;
        LOG.info("Login user {} at Salesforce loginUrl: {}", config.getUserName(), loginUrl);
        final Fields fields = new Fields(true);

        fields.put("grant_type", "password");
        fields.put("client_id", config.getClientId());
        fields.put("client_secret", config.getClientSecret());
        fields.put("username", config.getUserName());
        fields.put("password", config.getPassword());
        fields.put("format", "json");

        final Request post;
        if (conversation == null) {
            post = httpClient.POST(loginUrl);
        } else {
            post = httpClient.newHttpRequest(conversation, URI.create(loginUrl))
                .method(HttpMethod.POST);
        }

        return post.content(new FormContentProvider(fields))
            .timeout(timeout, TimeUnit.MILLISECONDS);
    }

    /**
     * Parses login response, allows SalesforceSecurityHandler to parse a login request for a failed authentication conversation.
     * @param loginResponse
     * @param responseContent
     * @throws SalesforceException
     */
    public synchronized void parseLoginResponse(ContentResponse loginResponse, String responseContent) throws SalesforceException {
        final int responseStatus = loginResponse.getStatus();

        try {
            switch (responseStatus) {
            case HttpStatus.OK_200:
                // parse the response to get token
                LoginToken token = objectMapper.readValue(responseContent, LoginToken.class);

                // don't log token or instance URL for security reasons
                LOG.info("Login successful");
                accessToken = token.getAccessToken();
                instanceUrl = token.getInstanceUrl();

                // notify all session listeners
                for (SalesforceSessionListener listener : listeners) {
                    try {
                        listener.onLogin(accessToken, instanceUrl);
                    } catch (Throwable t) {
                        LOG.warn("Unexpected error from listener {}: {}", listener, t.getMessage());
                    }
                }

                break;

            case HttpStatus.BAD_REQUEST_400:
                // parse the response to get error
                final LoginError error = objectMapper.readValue(responseContent, LoginError.class);
                final String msg = String.format("Login error code:[%s] description:[%s]",
                    error.getError(), error.getErrorDescription());
                final List<RestError> errors = new ArrayList<RestError>();
                errors.add(new RestError(msg, error.getErrorDescription()));
                throw new SalesforceException(errors, HttpStatus.BAD_REQUEST_400);

            default:
                throw new SalesforceException(String.format("Login error status:[%s] reason:[%s]",
                    responseStatus, loginResponse.getReason()), responseStatus);
            }
        } catch (IOException e) {
            String msg = "Login error: response parse exception " + e.getMessage();
            throw new SalesforceException(msg, e);
        }
    }

    public synchronized void logout() throws SalesforceException {
        if (accessToken == null) {
            return;
        }

        try {
            String logoutUrl = (instanceUrl == null ? config.getLoginUrl() : instanceUrl) + OAUTH2_REVOKE_PATH + accessToken;
            final Request logoutGet = httpClient.newRequest(logoutUrl)
                .timeout(timeout, TimeUnit.MILLISECONDS);
            final ContentResponse logoutResponse = logoutGet.send();

            final int statusCode = logoutResponse.getStatus();
            final String reason = logoutResponse.getReason();

            if (statusCode == HttpStatus.OK_200) {
                LOG.info("Logout successful");
            } else {
                throw new SalesforceException(
                        String.format("Logout error, code: [%s] reason: [%s]",
                                statusCode, reason),
                        statusCode);
            }

        } catch (InterruptedException e) {
            String msg = "Logout error: " + e.getMessage();
            throw new SalesforceException(msg, e);
        } catch (ExecutionException e) {
            final Throwable ex = e.getCause();
            throw new SalesforceException("Unexpected logout exception: " + ex.getMessage(), ex);
        } catch (TimeoutException e) {
            throw new SalesforceException("Logout request TIMEOUT!", null);
        } finally {
            // reset session
            accessToken = null;
            instanceUrl = null;
            // notify all session listeners about logout
            for (SalesforceSessionListener listener : listeners) {
                try {
                    listener.onLogout();
                } catch (Throwable t) {
                    LOG.warn("Unexpected error from listener {}: {}", listener, t.getMessage());
                }
            }
        }
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getInstanceUrl() {
        return instanceUrl;
    }

    public boolean addListener(SalesforceSessionListener listener) {
        return listeners.add(listener);
    }

    public boolean removeListener(SalesforceSessionListener listener) {
        return listeners.remove(listener);
    }

    @Override
    public void start() throws Exception {
        // auto-login at start if needed
        login(accessToken);
    }

    @Override
    public void stop() throws Exception {
        // logout
        logout();
    }

    public long getTimeout() {
        return timeout;
    }

    public interface SalesforceSessionListener {
        void onLogin(String accessToken, String instanceUrl);

        void onLogout();
    }
}
