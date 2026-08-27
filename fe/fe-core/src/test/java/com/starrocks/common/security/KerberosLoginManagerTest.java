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

package com.starrocks.common.security;

import com.starrocks.common.Config;
import com.starrocks.common.InvalidConfException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public class KerberosLoginManagerTest {
    @TempDir
    private Path tempDir;

    private final String savedPrincipal = Config.kerberos_principal;
    private final String savedKeytab = Config.kerberos_keytab;

    @AfterEach
    public void restoreConfig() {
        Config.kerberos_principal = savedPrincipal;
        Config.kerberos_keytab = savedKeytab;
    }

    @Test
    public void testDisabledByDefault() throws Exception {
        Config.kerberos_principal = "";
        Config.kerberos_keytab = "";

        KerberosLoginManager.loginIfConfigured();

        Assertions.assertFalse(KerberosLoginManager.isLoggedIn());
    }

    @Test
    public void testPrincipalWithoutKeytab() {
        Config.kerberos_principal = "starrocks/_HOST@EXAMPLE.COM";
        Config.kerberos_keytab = "";

        Assertions.assertThrows(InvalidConfException.class, KerberosLoginManager::loginIfConfigured);
    }

    @Test
    public void testKeytabWithoutPrincipal() throws Exception {
        Config.kerberos_principal = "";
        Config.kerberos_keytab = Files.createFile(tempDir.resolve("starrocks.keytab")).toString();

        Assertions.assertThrows(InvalidConfException.class, KerberosLoginManager::loginIfConfigured);
    }

    @Test
    public void testMissingKeytabFile() {
        Config.kerberos_principal = "starrocks/_HOST@EXAMPLE.COM";
        Config.kerberos_keytab = tempDir.resolve("absent.keytab").toString();

        Assertions.assertThrows(InvalidConfException.class, KerberosLoginManager::loginIfConfigured);
    }

    @Test
    public void testDirectoryAsKeytab() throws Exception {
        File directory = Files.createDirectory(tempDir.resolve("keytabs")).toFile();
        Config.kerberos_principal = "starrocks/_HOST@EXAMPLE.COM";
        Config.kerberos_keytab = directory.getAbsolutePath();

        Assertions.assertThrows(InvalidConfException.class, KerberosLoginManager::loginIfConfigured);
    }
}
