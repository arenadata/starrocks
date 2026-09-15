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
import com.baidu.jprotobuf.pbrpc.transport.RpcClientPipelineinitializer;
import io.netty.channel.Channel;
import io.netty.handler.ssl.SslContext;

/**
 * jprotobuf-rpc-core builds a plaintext pipeline only, so TLS is layered on top of the stock one.
 */
public class BrpcSslPipelineInitializer extends RpcClientPipelineinitializer {
    private final SslContext sslContext;

    public BrpcSslPipelineInitializer(RpcClient rpcClient, SslContext sslContext) {
        super(rpcClient);
        this.sslContext = sslContext;
    }

    @Override
    protected void initChannel(Channel channel) throws Exception {
        super.initChannel(channel);
        channel.pipeline().addFirst(new BrpcSslHandlerInstaller(sslContext));
    }
}
