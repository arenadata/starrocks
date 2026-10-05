// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.membership;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starrocks.common.Config;
import com.starrocks.common.util.NetUtils;
import com.starrocks.ha.FrontendNodeType;
import com.starrocks.membership.MembershipJoiner.JoinOutcome;
import com.starrocks.membership.MembershipJoiner.LeaderInfo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Membership API client over plain HTTP on the FE http_port. Redirects of a join request to the
 * current leader are followed once.
 */
final class HttpLeaderClient implements MembershipJoiner.LeaderClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final Duration timeout;

    HttpLeaderClient() {
        this.timeout = Duration.ofMillis(Config.fe_membership_http_timeout_ms);
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Optional<LeaderInfo> leader(HostPort seed) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri(seed.host(), Config.http_port, MembershipApi.LEADER_PATH))
                .timeout(timeout)
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 503) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IOException("GET " + MembershipApi.LEADER_PATH + " on " + seed + " returned "
                    + response.statusCode() + " " + errorMessage(response.body()));
        }
        JsonNode node = MAPPER.readTree(response.body());
        return Optional.of(new LeaderInfo(
                HostPort.parse(node.path("leader").asText()),
                node.path("leader_http_port").asInt(Config.http_port),
                node.path("cluster_id").asText(),
                node.path("run_mode").asText()));
    }

    @Override
    public JoinOutcome join(HostPort leaderHttp, HostPort self, FrontendNodeType role) throws IOException {
        String body = MAPPER.writeValueAsString(Map.of(
                "type", MembershipApi.TYPE_FRONTEND,
                "host", self.host(),
                "port", self.port(),
                "role", role.name()));
        URI joinUri = uri(leaderHttp.host(), leaderHttp.port(), MembershipApi.JOIN_PATH);
        HttpResponse<String> response = send(joinRequest(joinUri, body));
        if (response.statusCode() == 307) {
            Optional<String> location = response.headers().firstValue("Location");
            if (location.isEmpty()) {
                return JoinOutcome.failed(307, "redirect without Location");
            }
            response = send(joinRequest(URI.create(location.get()), body));
        }
        if (response.statusCode() != 200) {
            return JoinOutcome.failed(response.statusCode(), errorMessage(response.body()));
        }
        JsonNode node = MAPPER.readTree(response.body());
        return new JoinOutcome(200, "",
                FrontendNodeType.valueOf(node.path("role").asText()),
                node.path("node_name").asText(),
                HostPort.parse(node.path("helper").asText()));
    }

    private HttpRequest joinRequest(URI uri, String body) {
        return HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header(MembershipApi.TOKEN_HEADER, Config.auth_token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while calling " + request.uri(), e);
        }
    }

    private static URI uri(String host, int port, String path) {
        return URI.create("http://" + NetUtils.getHostPortInAccessibleFormat(host, port) + path);
    }

    private static String errorMessage(String body) {
        try {
            return MAPPER.readTree(body).path("msg").asText(body);
        } catch (IOException e) {
            return body;
        }
    }
}
