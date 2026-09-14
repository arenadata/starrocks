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

package com.starrocks.rpc;

import com.starrocks.common.Config;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

class BrpcSslHandlerInstallerTest {
    private final int savedHandshakeTimeout = Config.brpc_ssl_handshake_timeout_ms;

    @AfterEach
    void restoreConfig() {
        Config.brpc_ssl_handshake_timeout_ms = savedHandshakeTimeout;
    }

    private static SslContext sslContext() throws Exception {
        return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
    }

    private static EmbeddedChannel channelWithInstaller() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addFirst(new BrpcSslHandlerInstaller(sslContext()));
        Assertions.assertNull(channel.pipeline().get(SslHandler.class));
        return channel;
    }

    @Test
    void testSslHandlerInstalledAtHeadOnConnect() throws Exception {
        EmbeddedChannel channel = channelWithInstaller();
        try {
            channel.connect(new InetSocketAddress("127.0.0.1", 8060));

            SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
            Assertions.assertNotNull(sslHandler);
            Assertions.assertEquals(BrpcSslHandlerInstaller.SSL_HANDLER_NAME, channel.pipeline().names().get(0));
            Assertions.assertTrue(sslHandler.engine().getUseClientMode());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void testConnectFutureWaitsForTheHandshake() throws Exception {
        EmbeddedChannel channel = channelWithInstaller();
        try {
            ChannelFuture connect = channel.connect(new InetSocketAddress("127.0.0.1", 8060));

            // the TCP connect succeeded, but the peer never answers the ClientHello
            Assertions.assertTrue(channel.isActive());
            Assertions.assertFalse(connect.isDone());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void testConnectFutureFailsWhenTheHandshakeFails() throws Exception {
        EmbeddedChannel channel = channelWithInstaller();
        try {
            ChannelFuture connect = channel.connect(new InetSocketAddress("127.0.0.1", 8060));
            channel.pipeline().get(SslHandler.class).handshakeFuture().cancel(false);

            Assertions.assertTrue(connect.isDone());
            Assertions.assertFalse(connect.isSuccess());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void testHandshakeTimeoutFollowsConfig() throws Exception {
        Config.brpc_ssl_handshake_timeout_ms = 1234;
        EmbeddedChannel channel = channelWithInstaller();
        try {
            channel.connect(new InetSocketAddress("127.0.0.1", 8060));
            Assertions.assertEquals(1234, channel.pipeline().get(SslHandler.class).getHandshakeTimeoutMillis());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void testUnsupportedPeerAddressFailsClosed() throws Exception {
        EmbeddedChannel channel = channelWithInstaller();
        try {
            ChannelFuture connect = channel.connect(new SocketAddress() {
                private static final long serialVersionUID = 1L;
            });

            Assertions.assertTrue(connect.isDone());
            Assertions.assertFalse(connect.isSuccess());
            Assertions.assertNull(channel.pipeline().get(SslHandler.class));
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
