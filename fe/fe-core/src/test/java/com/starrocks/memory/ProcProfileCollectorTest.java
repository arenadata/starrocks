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

package com.starrocks.memory;

import com.starrocks.common.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mockStatic;

class ProcProfileCollectorTest {

    private static final String EXTRACT_PATH_PROPERTY = "one.profiler.extractPath";

    @TempDir
    public File tempDir;

    private String prevExtractPath;

    @BeforeEach
    public void saveAndClearExtractPath() {
        prevExtractPath = System.getProperty(EXTRACT_PATH_PROPERTY);
        System.clearProperty(EXTRACT_PATH_PROPERTY);
    }

    @AfterEach
    public void restoreExtractPath() {
        if (prevExtractPath == null) {
            System.clearProperty(EXTRACT_PATH_PROPERTY);
        } else {
            System.setProperty(EXTRACT_PATH_PROPERTY, prevExtractPath);
        }
    }

    @Test
    public void runAfterCatalogReady() throws IOException, InterruptedException {
        String prevSysLogDir = Config.sys_log_dir;
        Config.sys_log_dir = tempDir.getAbsolutePath();
        Config.proc_profile_collect_time_s = 1;
        try {
            ProcProfileCollector collector = new ProcProfileCollector();
            String dir = collector.getProfileLogDir();
            File profileDir = new File(dir);

            // count the number of files in the directory
            Function<Void, Integer> countFiles = (x) -> {
                if (!profileDir.exists() || !profileDir.isDirectory()) {
                    return 0;
                }
                int count = 0;
                String[] files = profileDir.list();
                if (files == null) {
                    return 0;
                }
                for (String filename : files) {
                    if (filename.startsWith("cpu-profile-") && filename.endsWith(".tar.gz")) {
                        count++;
                    }
                }
                return count;
            };

            int prevCount = countFiles.apply(null);

            collector.runAfterCatalogReady();
            assertTrue(profileDir.exists(), "Profile log directory should exist");
            assertTrue(profileDir.isDirectory(), "Profile log directory should be a directory");

            collector.runAfterCatalogReady();
            int afterCount = countFiles.apply(null);
            assertTrue(afterCount > prevCount, String.format("prev: %d, after: %d", prevCount, afterCount));
        } finally {
            Config.sys_log_dir = prevSysLogDir;
        }
    }

    @Test
    public void prepareLibraryPrefersWritableBin(@TempDir File home) {
        assertTrue(new File(home, "bin").mkdirs());

        new ProcProfileCollector().prepareLibrary(home.getAbsolutePath());

        assertEquals(home.getAbsolutePath() + "/bin/", System.getProperty(EXTRACT_PATH_PROPERTY));
    }

    @Test
    public void prepareLibraryFallsBackWhenBinIsNotWritable(@TempDir File home) {
        assertTrue(new File(home, "bin").mkdirs());
        // CALLS_REAL_METHODS keeps isDirectory real and forces only the writability check off:
        // a real directory cannot guarantee that when the CI runs as root (CAP_DAC_OVERRIDE
        // lets root write through permission bits).
        ProcProfileCollector collector = new ProcProfileCollector();
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.isWritable(any(Path.class))).thenReturn(false);

            collector.prepareLibrary(home.getAbsolutePath());
        }

        assertNull(System.getProperty(EXTRACT_PATH_PROPERTY),
                "the property must stay unset so the library default applies");

        // the fallback state must not stick: once bin/ becomes usable (an administrator
        // fixes permissions on a bare-metal host), the next tick picks it up
        collector.prepareLibrary(home.getAbsolutePath());
        assertEquals(home.getAbsolutePath() + "/bin/", System.getProperty(EXTRACT_PATH_PROPERTY),
                "a later repaired bin/ must be picked up");
    }

    @Test
    public void prepareLibraryKeepsAUserProvidedExtractPath(@TempDir File home) {
        assertTrue(new File(home, "bin").mkdirs());
        System.setProperty(EXTRACT_PATH_PROPERTY, "/custom/path");

        new ProcProfileCollector().prepareLibrary(home.getAbsolutePath());

        assertEquals("/custom/path", System.getProperty(EXTRACT_PATH_PROPERTY));
    }

    @Test
    public void prepareLibraryWithoutHomeDoesNothing() {
        new ProcProfileCollector().prepareLibrary(null);

        assertNull(System.getProperty(EXTRACT_PATH_PROPERTY));
    }
}
