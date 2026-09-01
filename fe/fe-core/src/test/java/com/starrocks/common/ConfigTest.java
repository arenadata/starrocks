// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.common;

import com.starrocks.common.util.Util;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class ConfigTest {
    private final Config config = new Config();
    // value returned by the mocked Util.isRunningInContainer(), see setUp()
    private static boolean runningInContainer = false;

    private static class ConfigForTest extends ConfigBase {
        @ConfField(mutable = true, aliases = {"schedule_slot_num_per_path", "schedule_slot_num_per_path_only_for_test"})
        public static int tablet_sched_slot_num_per_path = 2;
    }

    @BeforeEach
    public void setUp() throws Exception {
        // the tests must not depend on whether they happen to run inside a container themselves
        runningInContainer = false;
        new MockUp<Util>() {
            @Mock
            public boolean isRunningInContainer() {
                return runningInContainer;
            }
        };
        loadTestConfigFile();
    }

    @AfterEach
    public void tearDown() throws Exception {
        // ConfigBase keeps whether configs can be persisted in a static field, reload a writable config file
        // so that a test using a read-only one does not leak that state into the tests that follow
        runningInContainer = false;
        loadTestConfigFile();
    }

    private void loadTestConfigFile() throws Exception {
        URL resource = getClass().getClassLoader().getResource("conf/config_test.properties");
        assert resource != null;
        config.init(Paths.get(resource.toURI()).toFile().getAbsolutePath());
    }

    @Test
    public void testGetConfigFromPropertyFile() throws DdlException {
        PatternMatcher matcher = PatternMatcher.createMysqlPattern("tablet_sched_slot_num_per_path", false);
        List<List<String>> configs = Config.getConfigInfo(matcher);
        Assertions.assertEquals("3", configs.get(0).get(2));
    }

    @Test
    public void testConfigGetCompatibleWithOldName() throws Exception {
        URL resource = getClass().getClassLoader().getResource("conf/config_test2.properties");
        assert resource != null;
        config.init(Paths.get(resource.toURI()).toFile().getAbsolutePath());
        PatternMatcher matcher = PatternMatcher.createMysqlPattern("schedule_slot_num_per_path", false);
        List<List<String>> configs = Config.getConfigInfo(matcher);
        Assertions.assertEquals(1, configs.size());
        Assertions.assertEquals("3", configs.get(0).get(2));
        Assertions.assertEquals(3, Config.tablet_sched_slot_num_per_path);
        Assertions.assertEquals("tablet_sched_slot_num_per_path", configs.get(0).get(0));
        Assertions.assertTrue(configs.get(0).get(1).contains("schedule_slot_num_per_path"));
    }

    @Test
    public void testMultiAlias() throws Exception {
        ConfigForTest configForTest = new ConfigForTest();
        URL resource = getClass().getClassLoader().getResource("conf/config_test3.properties");
        assert resource != null;
        configForTest.init(Paths.get(resource.toURI()).toFile().getAbsolutePath());
        PatternMatcher matcher = PatternMatcher.createMysqlPattern("schedule_slot_num_per_path_only_for_test", false);
        List<List<String>> configs = ConfigForTest.getConfigInfo(matcher);
        Assertions.assertEquals(1, configs.size());
        Assertions.assertEquals("5", configs.get(0).get(2));
        Assertions.assertEquals(5, ConfigForTest.tablet_sched_slot_num_per_path);
        Assertions.assertTrue(configs.get(0).get(1).contains("schedule_slot_num_per_path_only_for_test"));
    }

    @Test
    public void testConfigSetCompatibleWithOldName() throws Exception {
        Config.setMutableConfig("schedule_slot_num_per_path", "4", false, "");
        PatternMatcher matcher = PatternMatcher.createMysqlPattern("schedule_slot_num_per_path", false);
        List<List<String>> configs = Config.getConfigInfo(matcher);
        Assertions.assertEquals("4", configs.get(0).get(2));
        Assertions.assertEquals(4, Config.tablet_sched_slot_num_per_path);
    }

    @Test
    public void testMutableConfig() throws Exception {
        // Skip test if persistence is not available (container environments)
        Assumptions.assumeTrue(ConfigBase.isIsPersisted(),
                "Skipping persistence test - not available in container environment");

        PatternMatcher matcher = PatternMatcher.createMysqlPattern("adaptive_choose_instances_threshold", false);
        List<List<String>> configs = Config.getConfigInfo(matcher);
        Assertions.assertEquals("99", configs.get(0).get(2));

        PatternMatcher matcher2 = PatternMatcher.createMysqlPattern("agent_task_resend_wait_time_ms", false);
        List<List<String>> configs2 = Config.getConfigInfo(matcher2);
        Assertions.assertEquals("998", configs2.get(0).get(2));

        Config.setMutableConfig("adaptive_choose_instances_threshold", "98", true, "root");
        configs = Config.getConfigInfo(matcher);
        Assertions.assertEquals("98", configs.get(0).get(2));
        Assertions.assertEquals(98, Config.adaptive_choose_instances_threshold);

        Config.setMutableConfig("agent_task_resend_wait_time_ms", "999", true, "root");
        configs2 = Config.getConfigInfo(matcher2);
        Assertions.assertEquals("999", configs2.get(0).get(2));
        Assertions.assertEquals(999, Config.agent_task_resend_wait_time_ms);
        // Write config twice
        Config.setMutableConfig("agent_task_resend_wait_time_ms", "1000", true, "root");
        configs2 = Config.getConfigInfo(matcher2);
        Assertions.assertEquals("1000", configs2.get(0).get(2));
        Assertions.assertEquals(1000, Config.agent_task_resend_wait_time_ms);

        // Reload from file
        URL resource = getClass().getClassLoader().getResource("conf/config_test.properties");
        config.init(Paths.get(resource.toURI()).toFile().getAbsolutePath());
        configs = Config.getConfigInfo(matcher);
        configs2 = Config.getConfigInfo(matcher2);
        Assertions.assertEquals("98", configs.get(0).get(2));
        Assertions.assertEquals("1000", configs2.get(0).get(2));
        Assertions.assertEquals(98, Config.adaptive_choose_instances_threshold);
        Assertions.assertEquals(1000, Config.agent_task_resend_wait_time_ms);
    }

    @Test
    public void testDisableStoreConfig() throws Exception {
        Config.setMutableConfig("adaptive_choose_instances_threshold", "98", false, "");
        PatternMatcher matcher = PatternMatcher.createMysqlPattern("adaptive_choose_instances_threshold", false);
        List<List<String>>  configs = Config.getConfigInfo(matcher);
        Assertions.assertEquals("98", configs.get(0).get(2));
        Assertions.assertEquals(98, Config.adaptive_choose_instances_threshold);

        // Reload from file
        URL resource = getClass().getClassLoader().getResource("conf/config_test.properties");
        config.init(Paths.get(resource.toURI()).toFile().getAbsolutePath());
        configs = Config.getConfigInfo(matcher);
        Assertions.assertEquals("99", configs.get(0).get(2));
        Assertions.assertEquals(99, Config.adaptive_choose_instances_threshold);
    }

    private static class ConfigForArray extends ConfigBase {

        @ConfField(mutable = true)
        public static short[] prop_array_short = new short[] {1, 1};
        @ConfField(mutable = true)
        public static int[] prop_array_int = new int[] {2, 2};
        @ConfField(mutable = true)
        public static long[] prop_array_long = new long[] {3L, 3L};
        @ConfField(mutable = true)
        public static double[] prop_array_double = new double[] {1.1, 1.1};
        @ConfField(mutable = true)
        public static String[] prop_array_string = new String[] {"1", "2"};
    }

    @Test
    public void testConfigArray() throws Exception {
        ConfigForArray configForArray = new ConfigForArray();
        URL resource = getClass().getClassLoader().getResource("conf/config_test3.properties");
        assert resource != null;
        configForArray.init(Paths.get(resource.toURI()).toFile().getAbsolutePath());
        List<List<String>> configs = ConfigForArray.getConfigInfo(null);
        Assertions.assertEquals("[1, 1]", configs.get(0).get(2));
        Assertions.assertEquals("short[]", configs.get(0).get(3));
        Assertions.assertEquals("[2, 2]", configs.get(1).get(2));
        Assertions.assertEquals("int[]", configs.get(1).get(3));
        Assertions.assertEquals("[3, 3]", configs.get(2).get(2));
        Assertions.assertEquals("long[]", configs.get(2).get(3));
        Assertions.assertEquals("[1.1, 1.1]", configs.get(3).get(2));
        Assertions.assertEquals("double[]", configs.get(3).get(3));
        Assertions.assertEquals("[1, 2]", configs.get(4).get(2));
        Assertions.assertEquals("String[]", configs.get(4).get(3));

        // check set an empty array works
        ConfigForArray.setConfigField(ConfigForArray.getAllMutableConfigs().get("prop_array_long"), "");
        configs = ConfigForArray.getConfigInfo(null);
        Assertions.assertEquals("[]", configs.get(2).get(2));
    }

    private Path writeTempConfigFile() throws Exception {
        Path confFile = Files.createTempFile("fe", ".conf");
        confFile.toFile().deleteOnExit();
        Files.writeString(confFile, "adaptive_choose_instances_threshold = 99\n");
        return confFile;
    }

    /**
     * `ADMIN SET FRONTEND CONFIG ... WITH PERSIST` has to be rejected with a message naming the reason when
     * fe.conf can not be written, which is the case when it comes from a read-only mount (ConfigMap/Secret
     * volume) or when the container runs with a read-only root filesystem.
     */
    @Test
    public void testSetPersistedConfigRejectedWhenConfigFileIsNotWritable() throws Exception {
        Path confFile = writeTempConfigFile();
        // a process running as root ignores the file permissions
        Assumptions.assumeTrue(confFile.toFile().setReadOnly(), "the config file can not be made read-only, skipping");
        Assumptions.assumeFalse(Files.isWritable(confFile), "the config file is writable, skipping");

        config.init(confFile.toString());
        Assertions.assertFalse(ConfigBase.isIsPersisted());

        int valueBefore = Config.adaptive_choose_instances_threshold;
        InvalidConfException e = Assertions.assertThrows(InvalidConfException.class,
                () -> Config.setMutableConfig("adaptive_choose_instances_threshold", "98", true, "root"));
        Assertions.assertTrue(e.getMessage().contains("adaptive_choose_instances_threshold"), e.getMessage());
        Assertions.assertTrue(e.getMessage().contains(confFile.toString()), e.getMessage());
        Assertions.assertTrue(e.getMessage().contains("is not writable"), e.getMessage());
        Assertions.assertTrue(e.getMessage().contains("WITH PERSIST"), e.getMessage());
        // the value is rejected as a whole, it is not applied in memory either
        Assertions.assertEquals(valueBefore, Config.adaptive_choose_instances_threshold);

        // the same config can still be changed in memory only
        Config.setMutableConfig("adaptive_choose_instances_threshold", "98", false, "root");
        Assertions.assertEquals(98, Config.adaptive_choose_instances_threshold);
    }

    /**
     * The images create /.dockerenv, a persisted value would be lost with the container, so persisting is
     * refused there even when fe.conf happens to be writable.
     */
    @Test
    public void testSetPersistedConfigRejectedInContainer() throws Exception {
        runningInContainer = true;

        Path confFile = writeTempConfigFile();
        Assertions.assertTrue(Files.isWritable(confFile));

        config.init(confFile.toString());
        Assertions.assertFalse(ConfigBase.isIsPersisted());

        InvalidConfException e = Assertions.assertThrows(InvalidConfException.class,
                () -> Config.setMutableConfig("adaptive_choose_instances_threshold", "98", true, "root"));
        Assertions.assertTrue(e.getMessage().contains("container"), e.getMessage());
        Assertions.assertTrue(e.getMessage().contains("WITH PERSIST"), e.getMessage());
        // the configuration file is left untouched
        Assertions.assertEquals("adaptive_choose_instances_threshold = 99\n", Files.readString(confFile));
    }

    /**
     * A writable fe.conf outside of a container keeps working: the value is applied and written back.
     */
    @Test
    public void testSetPersistedConfigWritesTheConfigFile() throws Exception {
        Path confFile = writeTempConfigFile();
        Assumptions.assumeTrue(Files.isWritable(confFile), "the config file is not writable, skipping");

        config.init(confFile.toString());
        Assertions.assertTrue(ConfigBase.isIsPersisted());
        Assertions.assertNull(ConfigBase.getPersistUnavailableReason());

        Config.setMutableConfig("adaptive_choose_instances_threshold", "98", true, "root");
        Assertions.assertEquals(98, Config.adaptive_choose_instances_threshold);
        Assertions.assertTrue(Files.readString(confFile).contains("adaptive_choose_instances_threshold = 98"));
    }
}
