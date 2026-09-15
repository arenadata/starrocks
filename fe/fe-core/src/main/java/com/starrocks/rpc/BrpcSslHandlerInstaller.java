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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Inserts the {@link SslHandler} at the head of the pipeline on connect. The pipeline is built at
 * channel registration, before the peer address is known.
 */
public class BrpcSslHandlerInstaller extends ChannelOutboundHandlerAdapter {
    public static final String SSL_HANDLER_NAME = "ssl";

    private final SslContext sslContext;

    public BrpcSslHandlerInstaller(SslContext sslContext) {
        this.sslContext = sslContext;
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress,
                        ChannelPromise promise) throws Exception {
        SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
        if (sslHandler == null) {
            sslHandler = newSslHandler(ctx, remoteAddress);
            ctx.pipeline().addFirst(SSL_HANDLER_NAME, sslHandler);
        }

        // the connection pool waits on the connect future alone, so a connection must not be
        // reported as established before its handshake succeeds
        SslHandler installed = sslHandler;
        ChannelPromise tcpConnect = ctx.newPromise();
        tcpConnect.addListener(connect -> {
            if (!connect.isSuccess()) {
                promise.tryFailure(connect.cause());
                return;
            }
            installed.handshakeFuture().addListener(handshake -> {
                if (handshake.isSuccess()) {
                    promise.trySuccess();
                } else {
                    promise.tryFailure(handshake.cause());
                }
            });
        });
        ctx.connect(remoteAddress, localAddress, tcpConnect);
    }

    private SslHandler newSslHandler(ChannelHandlerContext ctx, SocketAddress remoteAddress) {
        if (!(remoteAddress instanceof InetSocketAddress)) {
            throw new IllegalArgumentException("bRPC TLS needs an InetSocketAddress peer, got " + remoteAddress);
        }
        InetSocketAddress peer = (InetSocketAddress) remoteAddress;
        SslHandler sslHandler = sslContext.newHandler(ctx.alloc(), peer.getHostString(), peer.getPort());
        sslHandler.setHandshakeTimeoutMillis(Config.brpc_ssl_handshake_timeout_ms);
        return sslHandler;
    }
}
