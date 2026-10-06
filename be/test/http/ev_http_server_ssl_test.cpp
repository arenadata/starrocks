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

#include <gtest/gtest.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/x509.h>
#include <stdlib.h>

#include <cstdio>
#include <filesystem>
#include <string>

#include "http/ev_http_server.h"
#include "http/http_channel.h"
#include "http/http_client.h"
#include "http/http_handler.h"
#include "http/http_request.h"
#include "testutil/assert.h"

namespace starrocks {

class EvHttpServerSslTestHandler : public HttpHandler {
public:
    void handle(HttpRequest* req) override { HttpChannel::send_reply(req, "metric_ok 1\n"); }
};

static EvHttpServerSslTestHandler s_handler;

// Writes a self-signed certificate and its private key as PEM files.
static void write_self_signed_cert(const std::string& cert_path, const std::string& key_path) {
    EVP_PKEY_CTX* pctx = EVP_PKEY_CTX_new_id(EVP_PKEY_RSA, nullptr);
    ASSERT_NE(nullptr, pctx);
    ASSERT_EQ(1, EVP_PKEY_keygen_init(pctx));
    ASSERT_EQ(1, EVP_PKEY_CTX_set_rsa_keygen_bits(pctx, 2048));
    EVP_PKEY* pkey = nullptr;
    ASSERT_EQ(1, EVP_PKEY_keygen(pctx, &pkey));
    EVP_PKEY_CTX_free(pctx);

    X509* x509 = X509_new();
    ASN1_INTEGER_set(X509_get_serialNumber(x509), 1);
    X509_gmtime_adj(X509_getm_notBefore(x509), -3600);
    X509_gmtime_adj(X509_getm_notAfter(x509), 24 * 3600);
    X509_set_pubkey(x509, pkey);
    X509_NAME* name = X509_get_subject_name(x509);
    X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC, (const unsigned char*)"127.0.0.1", -1, -1, 0);
    X509_set_issuer_name(x509, name);
    ASSERT_GT(X509_sign(x509, pkey, EVP_sha256()), 0);

    FILE* f = fopen(cert_path.c_str(), "wb");
    ASSERT_NE(nullptr, f);
    ASSERT_EQ(1, PEM_write_X509(f, x509));
    fclose(f);
    f = fopen(key_path.c_str(), "wb");
    ASSERT_NE(nullptr, f);
    ASSERT_EQ(1, PEM_write_PrivateKey(f, pkey, nullptr, nullptr, 0, nullptr, nullptr));
    fclose(f);

    X509_free(x509);
    EVP_PKEY_free(pkey);
}

class EvHttpServerSslTest : public testing::Test {
public:
    static void SetUpTestCase() {
        char tmpl[] = "/tmp/ev_http_server_ssl_test_XXXXXX";
        ASSERT_NE(nullptr, mkdtemp(tmpl));
        s_dir = tmpl;
        s_cert = s_dir + "/cert.pem";
        s_key = s_dir + "/key.pem";
        s_other_cert = s_dir + "/other_cert.pem";
        s_other_key = s_dir + "/other_key.pem";
        write_self_signed_cert(s_cert, s_key);
        write_self_signed_cert(s_other_cert, s_other_key);
    }

    static void TearDownTestCase() { std::filesystem::remove_all(s_dir); }

protected:
    static Status get(const std::string& url, std::string* response) {
        HttpClient client;
        RETURN_IF_ERROR(client.init(url));
        client.set_method(GET);
        client.set_timeout_ms(5000);
        client.trust_all_ssl();
        return client.execute(response);
    }

    static std::string s_dir;
    static std::string s_cert;
    static std::string s_key;
    static std::string s_other_cert;
    static std::string s_other_key;
};

std::string EvHttpServerSslTest::s_dir;
std::string EvHttpServerSslTest::s_cert;
std::string EvHttpServerSslTest::s_key;
std::string EvHttpServerSslTest::s_other_cert;
std::string EvHttpServerSslTest::s_other_key;

#ifdef STARROCKS_HAVE_EVHTTP_SSL

TEST_F(EvHttpServerSslTest, serve_https) {
    EvHttpServer server(0, 1, EvHttpServerSslConfig{s_cert, s_key});
    server.register_handler(GET, "/metrics", &s_handler);
    ASSERT_OK(server.start());
    int port = server.get_real_port();
    ASSERT_NE(0, port);

    std::string response;
    ASSERT_OK(get("https://127.0.0.1:" + std::to_string(port) + "/metrics", &response));
    ASSERT_EQ("metric_ok 1\n", response);

    // The HTTPS port must not serve plaintext.
    response.clear();
    ASSERT_FALSE(get("http://127.0.0.1:" + std::to_string(port) + "/metrics", &response).ok());

    // The server keeps serving HTTPS after a plaintext attempt.
    response.clear();
    ASSERT_OK(get("https://127.0.0.1:" + std::to_string(port) + "/metrics", &response));
    ASSERT_EQ("metric_ok 1\n", response);

    server.stop();
    server.join();
}

TEST_F(EvHttpServerSslTest, mismatched_key) {
    EvHttpServer server(0, 1, EvHttpServerSslConfig{s_cert, s_other_key});
    // OpenSSL rejects the key either when loading it or in SSL_CTX_check_private_key.
    ASSERT_FALSE(server.start().ok());
}

TEST_F(EvHttpServerSslTest, missing_files) {
    {
        EvHttpServer server(0, 1, EvHttpServerSslConfig{s_dir + "/no_such_cert.pem", s_key});
        ASSERT_FALSE(server.start().ok());
    }
    {
        EvHttpServer server(0, 1, EvHttpServerSslConfig{s_cert, s_dir + "/no_such_key.pem"});
        ASSERT_FALSE(server.start().ok());
    }
    {
        EvHttpServer server(0, 1, EvHttpServerSslConfig{"", ""});
        ASSERT_FALSE(server.start().ok());
    }
}

#else

TEST_F(EvHttpServerSslTest, not_supported) {
    EvHttpServer server(0, 1, EvHttpServerSslConfig{s_cert, s_key});
    ASSERT_TRUE(server.start().is_not_supported());
}

#endif

TEST_F(EvHttpServerSslTest, plain_http_without_ssl_config) {
    EvHttpServer server(0, 1);
    server.register_handler(GET, "/metrics", &s_handler);
    ASSERT_OK(server.start());
    int port = server.get_real_port();
    ASSERT_NE(0, port);

    std::string response;
    ASSERT_OK(get("http://127.0.0.1:" + std::to_string(port) + "/metrics", &response));
    ASSERT_EQ("metric_ok 1\n", response);

    server.stop();
    server.join();
}

} // namespace starrocks
