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

package com.starrocks.membership.zookeeper;

import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/** Standalone in-process ZooKeeper: its handle allows expiring a client session on purpose. */
public class InProcessZooKeeper implements Closeable {
    private final ZooKeeperServer server;
    private final NIOServerCnxnFactory factory;

    public InProcessZooKeeper(Path dataDir) throws IOException, InterruptedException {
        Files.createDirectories(dataDir);
        server = new ZooKeeperServer(dataDir.toFile(), dataDir.toFile(), 2000);
        factory = new NIOServerCnxnFactory();
        factory.configure(new InetSocketAddress("127.0.0.1", 0), 0);
        factory.startup(server);
    }

    public String connectString() {
        return "127.0.0.1:" + factory.getLocalPort();
    }

    public void closeSession(long sessionId) {
        server.closeSession(sessionId);
    }

    @Override
    public void close() throws IOException {
        factory.shutdown();
        server.getZKDatabase().close();
    }
}
