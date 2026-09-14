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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.starrocks.common.Config;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import javax.net.ssl.TrustManagerFactory;

/**
 * Client side TLS material for the bRPC connections to BE/CN.
 */
public class BrpcSslContextLoader {
    private static final Logger LOG = LogManager.getLogger(BrpcSslContextLoader.class);

    private static SslContext sslContext;

    /**
     * Called during FE startup so that a broken configuration aborts the boot instead of surfacing
     * on the first RPC.
     */
    public static synchronized void load() {
        if (!Config.brpc_enable_ssl) {
            return;
        }
        try {
            sslContext = createSslContext();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to create the bRPC client SSL context. Please check "
                    + "brpc_ssl_truststore_location and brpc_ssl_truststore_password: " + e.getMessage(), e);
        }
        LOG.info("bRPC client TLS enabled, truststore={}, verify hostname={}",
                Config.brpc_ssl_truststore_location, Config.brpc_ssl_verify_hostname);
    }

    public static synchronized SslContext getSslContext() {
        return sslContext;
    }

    @VisibleForTesting
    static SslContext createSslContext() throws Exception {
        if (Strings.isNullOrEmpty(Config.brpc_ssl_truststore_location)) {
            throw new IllegalArgumentException("brpc_ssl_truststore_location is empty; it must hold the CA "
                    + "certificates that signed the BE/CN bRPC certificates");
        }
        return SslContextBuilder.forClient()
                .trustManager(createTrustManagerFactory())
                .endpointIdentificationAlgorithm(Config.brpc_ssl_verify_hostname ? "HTTPS" : null)
                .build();
    }

    private static TrustManagerFactory createTrustManagerFactory() throws Exception {
        String location = Config.brpc_ssl_truststore_location;
        // an empty password still runs the integrity check; a null one would skip it
        char[] password = Strings.nullToEmpty(Config.brpc_ssl_truststore_password).toCharArray();

        KeyStore trustStore = KeyStore.getInstance("JKS");
        try (InputStream trustStoreIS = new FileInputStream(location)) {
            trustStore.load(trustStoreIS, password);
        }
        if (trustStore.size() == 0) {
            throw new IllegalArgumentException("truststore " + location + " holds no certificate");
        }
        TrustManagerFactory trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        return trustManagerFactory;
    }

    @VisibleForTesting
    static synchronized void reset() {
        sslContext = null;
    }
}
