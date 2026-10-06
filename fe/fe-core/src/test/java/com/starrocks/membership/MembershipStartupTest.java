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

package com.starrocks.membership;

import com.starrocks.common.Config;
import com.starrocks.common.Pair;
import com.starrocks.ha.FrontendNodeType;
import com.starrocks.server.NodeMgr;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * NodeMgr startup with a membership provider: the bootstrap decision and the helper handling.
 */
public class MembershipStartupTest {

    @TempDir
    Path tempDir;

    private String previousMetaDir;

    @BeforeAll
    public static void setUpClass() {
        UtFrameUtils.setUpForPersistTest();
    }

    @BeforeEach
    public void setUp() throws Exception {
        previousMetaDir = Config.meta_dir;
        Files.createDirectories(tempDir.resolve("image"));
        Config.meta_dir = tempDir.toString();
    }

    @AfterEach
    public void tearDown() {
        MembershipProviders.close();
        Config.meta_dir = previousMetaDir;
        Config.fe_cluster_initial_state = "existing";
    }

    @Test
    public void testBootstrapThenRestartWithSeveralHelpers() throws Exception {
        NodeMgr first = new NodeMgr();
        first.initialize(null);
        FakeMembershipProvider alone = new FakeMembershipProvider();
        alone.candidate = true;
        MembershipProviders.setForTest(alone,
                new MembershipContext(HostPort.of(first.getSelfNode()), FrontendNodeType.FOLLOWER, false));
        Config.fe_cluster_initial_state = "new";

        Assertions.assertTrue(first.isVersionAndRoleFilesNotExist());
        first.getClusterIdAndRoleOnStartup();
        Assertions.assertTrue(first.isFirstTimeStartUp());
        Assertions.assertEquals(FrontendNodeType.FOLLOWER, first.getRole());
        Assertions.assertEquals(List.of(first.getSelfNode()), first.getHelperNodes());
        Assertions.assertFalse(first.isVersionAndRoleFilesNotExist());

        // a restart with existing meta ignores the helpers and keeps a single one for the bdbje handshake
        NodeMgr restarted = new NodeMgr();
        restarted.initialize("10.0.0.1:9010,10.0.0.2:9010");
        restarted.getClusterIdAndRoleOnStartup();
        Assertions.assertFalse(restarted.isFirstTimeStartUp());
        Assertions.assertEquals(first.getNodeName(), restarted.getNodeName());
        Assertions.assertEquals(List.of(Pair.create("10.0.0.1", 9010)), restarted.getHelperNodes());
    }
}
