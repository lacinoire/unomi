/*
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
package org.apache.unomi.shell.migration.impl;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.karaf.shell.api.console.Session;
import org.apache.unomi.shell.migration.Migration;
import org.apache.unomi.shell.migration.utils.ConsoleUtils;
import org.json.JSONObject;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;
import org.osgi.service.component.annotations.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
public class MigrationTo200 implements Migration {

    private CloseableHttpClient httpClient;
    private Session session;
    private String esAddress;
    private BundleContext bundleContext;

    @Override
    public Version getFromVersion() {
        return new Version("1.5.0");
    }

    @Override
    public Version getToVersion() {
        return new Version("2.0.0");
    }

    @Override
    public String getDescription() {
        return "Updates mapping for an index \"event\" with prefix \"context\" by default. Adds the \"sourceId\" field and copies value "
                + "from the \"scope\" field to it."
                + "Creates the scope entries in the index \"scope\" from the existing sopes of the events";
    }

    @Override
    public void execute(Session session, CloseableHttpClient httpClient, String esAddress, BundleContext bundleContext) throws IOException {
        this.httpClient = httpClient;
        this.session = session;
        this.esAddress = esAddress;
        this.bundleContext = bundleContext;

        doExecute();
    }

    private void doExecute() throws IOException {
        String indexPrefix = ConsoleUtils.askUserWithDefaultAnswer(session, "SOURCE index name (default: context) : ", "context");
        Set<String> indexes = getEventIndexes(indexPrefix);
        for (String index : indexes) {
            updateMapping(index);
        }
        createScopeMapping(indexPrefix);
        createScopes(getSetOfScopes(indexes), indexPrefix);
    }

    private void updateMapping(final String indexName) throws IOException {
        HttpPut httpPut = new HttpPut(esAddress + "/" + indexName + "/_mapping");

        httpPut.addHeader("Accept", "application/json");
        httpPut.addHeader("Content-Type", "application/json");

        String requestBody = resourceAsString("requestBody/updateMapping.json");

        httpPut.setEntity(new StringEntity(requestBody));

        try (CloseableHttpResponse response = httpClient.execute(httpPut)) {
            JSONObject responseAsJson = new JSONObject(EntityUtils.toString(response.getEntity()));

            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK && responseAsJson.has("acknowledged") && responseAsJson
                    .getBoolean("acknowledged")) {
                System.out.println("Mapping for index = \"" + indexName + "\" successfully updated.");

                copyValueScopeToSourceId(indexName, httpClient);
            } else {
                System.out.println("Update the mapping for index = \"" + indexName + "\" failed.");
            }
        }
    }

    private void copyValueScopeToSourceId(final String indexName, final CloseableHttpClient httpClient) throws IOException {
        final HttpPost httpPost = new HttpPost(esAddress + "/" + indexName + "/_update_by_query");

        httpPost.addHeader("Accept", "application/json");
        httpPost.addHeader("Content-Type", "application/json");

        String requestBody = resourceAsString("requestBody/copyValueScopeToSourceId.json");

        httpPost.setEntity(new StringEntity(requestBody));

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            JSONObject responseAsJson = new JSONObject(EntityUtils.toString(response.getEntity()));

            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                System.out.println("Copying the \"scope\" field to the \"sourceId\" field for index = \"" + indexName
                        + "\" successfully completed. Total: " + responseAsJson.get("total") + ", updated: " + responseAsJson.get("updated")
                        + ".");
            } else {
                System.out.println("Copying the \"scope\" field to the \"sourceId\" field for index = \"" + indexName + "\" failed.");
            }
        }
    }

    private Set<String> getEventIndexes(String indexPrefix) throws IOException {
        try (CloseableHttpResponse response = httpClient.execute(new HttpGet(esAddress + "/_aliases"))) {
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                JSONObject indexesAsJson = new JSONObject(EntityUtils.toString(response.getEntity()));
                return indexesAsJson.keySet().stream().
                        filter(alias -> alias.startsWith(indexPrefix + "-event")).
                        collect(Collectors.toSet());
            }
        }
        return Collections.emptySet();
    }

    private boolean scopeIndexNotExists(String indexPrefix) throws IOException {
        final HttpGet httpGet = new HttpGet(esAddress + "/" + indexPrefix + "-scope");

        httpGet.addHeader("Accept", "application/json");
        httpGet.addHeader("Content-Type", "application/json");

        try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
            return response.getStatusLine().getStatusCode() != HttpStatus.SC_OK;
        }
    }

    private void createScopeMapping(String indexPrefix) throws IOException {

        if (scopeIndexNotExists(indexPrefix)) {
            System.out.println("Creation for index = \"" + indexPrefix + "-scope\" starting.");
            System.out.println("Specify the following parameters:");
            String numberOfShards = ConsoleUtils.askUserWithDefaultAnswer(session, "number_of_shards: (default: 3)", "3");
            String numberOfReplicas = ConsoleUtils.askUserWithDefaultAnswer(session, "number_of_replicas: (default: 0)", "0");
            String mappingTotalFieldsLimit = ConsoleUtils
                    .askUserWithDefaultAnswer(session, "mapping.total_fields.limit: (default: 1000)", "1000");
            String maxDocValueFieldsSearch = ConsoleUtils
                    .askUserWithDefaultAnswer(session, "max_docvalue_fields_search: (default: 1000)", "1000");

            final HttpPut httpPost = new HttpPut(esAddress + "/" + indexPrefix + "-scope");

            httpPost.addHeader("Accept", "application/json");
            httpPost.addHeader("Content-Type", "application/json");

            String request = resourceAsString("requestBody/scopeMapping.json").replace("$numberOfShards", numberOfShards)
                    .replace("$numberOfReplicas", numberOfReplicas).replace("$mappingTotalFieldsLimit", mappingTotalFieldsLimit)
                    .replace("$maxDocValueFieldsSearch", maxDocValueFieldsSearch);

            httpPost.setEntity(new StringEntity(request));

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                    System.out.println(indexPrefix + "-scope has been correctly created");
                } else {
                    System.out.println(
                            "Failed to create the index " + indexPrefix + "-scope.Code:" + response.getStatusLine().getStatusCode());
                    throw new RuntimeException("Can not create the scope index. Stop the execution of the migration.");
                }
            }
        } else {
            System.out.println("The scope index already exists. Skipping the creation of this index");
        }

    }

    private void createScopes(Set<String> scopes, String indexPrefix) throws IOException {
        final StringBuilder body = new StringBuilder();
        String saveScopeBody = resourceAsString("requestBody/bulkSaveScope.ndjson");
        scopes.forEach(scope -> body.append(saveScopeBody.replace("$scope", scope)));

        final HttpPost httpPost = new HttpPost(esAddress + "/" + indexPrefix + "-scope/_bulk");

        httpPost.addHeader("Accept", "application/json");
        httpPost.addHeader("Content-Type", "application/x-ndjson");

        httpPost.setEntity(new StringEntity(body.toString()));

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                System.out.println("Creating the \"scopes\" into the index " + indexPrefix + "-scope successfully finished");
            } else {
                System.out.println("Creating the \"scopes\" into the index " + indexPrefix + "-scope has failed" + response.getStatusLine()
                        .getStatusCode());
            }
        }
    }

    private Set<String> getSetOfScopes(Set<String> indices) throws IOException {
        String joinedIndices = String.join(",", indices);
        final HttpPost httpPost = new HttpPost(esAddress + "/" + joinedIndices + "/_search");

        httpPost.addHeader("Accept", "application/json");
        httpPost.addHeader("Content-Type", "application/json");

        String request = resourceAsString("requestBody/searchScope.json");

        httpPost.setEntity(new StringEntity(request));

        Set<String> scopes = new HashSet<>();
        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            JSONObject responseAsJson = new JSONObject(EntityUtils.toString(response.getEntity()));
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                System.out.println("Getting the \"scope\" values from the events successfully finished. " + "Number of scope to create: "
                        + responseAsJson.getJSONObject("aggregations").getJSONObject("bucketInfos").get("count").toString());
                scopes = StreamSupport
                        .stream(responseAsJson.getJSONObject("aggregations").getJSONObject("scopes").getJSONArray("buckets").spliterator(),
                                false).map(bucketElement -> ((JSONObject) bucketElement).getString("key")).collect(Collectors.toSet());
            } else {
                System.out.println(
                        "Getting the \"scope\" values from the event has failed. Code: " + response.getStatusLine().getStatusCode());
            }
        }
        return scopes;
    }

    protected String resourceAsString(final String resource) {
        final URL url = bundleContext.getBundle().getResource(resource);
        try (InputStream stream = url.openStream()) {
            return IOUtils.toString(stream, StandardCharsets.UTF_8);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }
}
