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
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

class BrpcSslContextLoaderTest {
    private final boolean savedEnabled = Config.brpc_enable_ssl;
    private final String savedLocation = Config.brpc_ssl_truststore_location;
    private final String savedPassword = Config.brpc_ssl_truststore_password;
    private final boolean savedVerifyHostname = Config.brpc_ssl_verify_hostname;

    @TempDir
    Path tempDir;

    @AfterEach
    void restoreConfig() {
        Config.brpc_enable_ssl = savedEnabled;
        Config.brpc_ssl_truststore_location = savedLocation;
        Config.brpc_ssl_truststore_password = savedPassword;
        Config.brpc_ssl_verify_hostname = savedVerifyHostname;
        BrpcSslContextLoader.reset();
    }

    /**
     * A truststore holding one of the JVM's own trust anchors, so the tests exercise the real
     * KeyStore and TrustManagerFactory path without generating a certificate.
     */
    private Path writeTruststore(String password) throws Exception {
        TrustManagerFactory defaultFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        defaultFactory.init((KeyStore) null);
        X509Certificate[] issuers = ((X509TrustManager) defaultFactory.getTrustManagers()[0]).getAcceptedIssuers();
        Assumptions.assumeTrue(issuers.length > 0, "JVM has no trust anchors to build a truststore from");

        KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(null, password.toCharArray());
        trustStore.setCertificateEntry("ca", issuers[0]);

        Path path = tempDir.resolve("truststore.jks");
        try (OutputStream out = Files.newOutputStream(path)) {
            trustStore.store(out, password.toCharArray());
        }
        return path;
    }

    @Test
    void testLoadIsNoOpWhenDisabled() {
        Config.brpc_enable_ssl = false;
        Config.brpc_ssl_truststore_location = "/nonexistent/truststore.jks";
        BrpcSslContextLoader.load();
        Assertions.assertNull(BrpcSslContextLoader.getSslContext());
    }

    @Test
    void testTruststoreIsMandatory() {
        Config.brpc_ssl_truststore_location = "";
        Exception e = Assertions.assertThrows(IllegalArgumentException.class, BrpcSslContextLoader::createSslContext);
        Assertions.assertTrue(e.getMessage().contains("brpc_ssl_truststore_location"), e.getMessage());
    }

    @Test
    void testMissingTruststoreAbortsStartup() {
        Config.brpc_enable_ssl = true;
        Config.brpc_ssl_truststore_location = "/nonexistent/truststore.jks";
        Exception e = Assertions.assertThrows(IllegalArgumentException.class, BrpcSslContextLoader::load);
        Assertions.assertInstanceOf(FileNotFoundException.class, e.getCause());
        Assertions.assertNull(BrpcSslContextLoader.getSslContext());
    }

    @Test
    void testLoadPublishesContext() throws Exception {
        Config.brpc_enable_ssl = true;
        Config.brpc_ssl_truststore_location = writeTruststore("truststorepw").toString();
        Config.brpc_ssl_truststore_password = "truststorepw";
        BrpcSslContextLoader.load();
        Assertions.assertNotNull(BrpcSslContextLoader.getSslContext());
    }

    @Test
    void testWrongPasswordFailsTheIntegrityCheck() throws Exception {
        Config.brpc_ssl_truststore_location = writeTruststore("truststorepw").toString();
        Config.brpc_ssl_truststore_password = "";
        Exception e = Assertions.assertThrows(IOException.class, BrpcSslContextLoader::createSslContext);
        Assertions.assertTrue(e.getMessage().contains("tampered") || e.getMessage().contains("password"),
                e.getMessage());
    }

    @Test
    void testEmptyTruststoreRejected() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(null, "truststorepw".toCharArray());
        Path path = tempDir.resolve("empty.jks");
        try (OutputStream out = Files.newOutputStream(path)) {
            trustStore.store(out, "truststorepw".toCharArray());
        }
        Config.brpc_ssl_truststore_location = path.toString();
        Config.brpc_ssl_truststore_password = "truststorepw";

        Exception e = Assertions.assertThrows(IllegalArgumentException.class, BrpcSslContextLoader::createSslContext);
        Assertions.assertTrue(e.getMessage().contains("holds no certificate"), e.getMessage());
    }

    @Test
    void testHostnameVerificationFollowsConfig() throws Exception {
        Config.brpc_ssl_truststore_location = writeTruststore("truststorepw").toString();
        Config.brpc_ssl_truststore_password = "truststorepw";

        Config.brpc_ssl_verify_hostname = false;
        Assertions.assertNull(endpointIdentificationAlgorithm(BrpcSslContextLoader.createSslContext()));

        Config.brpc_ssl_verify_hostname = true;
        Assertions.assertEquals("HTTPS", endpointIdentificationAlgorithm(BrpcSslContextLoader.createSslContext()));
    }

    private static String endpointIdentificationAlgorithm(SslContext sslContext) {
        SSLEngine engine = sslContext.newEngine(UnpooledByteBufAllocator.DEFAULT);
        return engine.getSSLParameters().getEndpointIdentificationAlgorithm();
    }
}
