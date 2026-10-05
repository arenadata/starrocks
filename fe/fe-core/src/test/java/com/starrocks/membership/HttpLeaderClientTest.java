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

import com.starrocks.common.Config;
import com.starrocks.ha.FrontendNodeType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class HttpLeaderClientTest {
    private HttpServer server;
    private int previousHttpPort;

    /** Serves 200 with the given bodies; a malformed 200 must surface as IOException, not a runtime crash. */
    private void startServer(String leaderBody, String joinBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestURI().getPath().endsWith("/join")
                    ? joinBody.getBytes(StandardCharsets.UTF_8)
                    : leaderBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        previousHttpPort = Config.http_port;
        Config.http_port = server.getAddress().getPort();
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            Config.http_port = previousHttpPort;
            server.stop(0);
        }
    }

    @Test
    public void testLeaderAnswerWithoutLeaderFieldIsRetryable() throws Exception {
        startServer("{\"cluster_id\": \"1\", \"run_mode\": \"shared_data\"}", "{}");
        HttpLeaderClient client = new HttpLeaderClient();
        Assertions.assertThrows(IOException.class, () -> client.leader(new HostPort("127.0.0.1", 9010)));
    }

    @Test
    public void testJoinAnswerWithUnknownRoleIsRetryable() throws Exception {
        startServer("{}", "{\"role\": \"BOGUS\", \"node_name\": \"x\", \"helper\": \"127.0.0.1:9010\"}");
        HttpLeaderClient client = new HttpLeaderClient();
        Assertions.assertThrows(IOException.class, () -> client.join(
                new HostPort("127.0.0.1", Config.http_port), new HostPort("127.0.0.1", 9010),
                FrontendNodeType.FOLLOWER));
    }
}
