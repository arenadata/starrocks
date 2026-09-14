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

import com.baidu.jprotobuf.pbrpc.transport.RpcClient;
import com.baidu.jprotobuf.pbrpc.transport.RpcClientOptions;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;

class BrpcSslPipelineInitializerTest {
    /**
     * Handlers the stock jprotobuf pipeline contributes; TLS has to sit in front of all of them.
     */
    private static final List<String> STOCK_HANDLERS = List.of(
            "client_data_encoder", "compress_handler", "idel_channal_handler", "frameDecoder",
            "client_data_decoder", "uncompress", "client_handler");

    @Test
    void testTlsWrapsTheStockPipeline() throws Exception {
        SslContext sslContext =
                SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        RpcClient rpcClient = new RpcClient(new RpcClientOptions());
        try {
            BrpcSslPipelineInitializer initializer = new BrpcSslPipelineInitializer(rpcClient, sslContext);
            rpcClient.handler(initializer);
            Assertions.assertSame(initializer, rpcClient.config().handler());

            EmbeddedChannel channel = new EmbeddedChannel();
            try {
                initializer.initChannel(channel);
                List<String> names = channel.pipeline().names();
                Assertions.assertTrue(names.containsAll(STOCK_HANDLERS), names.toString());

                channel.connect(new InetSocketAddress("127.0.0.1", 8060));
                Assertions.assertEquals(BrpcSslHandlerInstaller.SSL_HANDLER_NAME, channel.pipeline().names().get(0));
                Assertions.assertNotNull(channel.pipeline().get(SslHandler.class));
            } finally {
                channel.finishAndReleaseAll();
            }
        } finally {
            rpcClient.shutdown();
        }
    }
}
