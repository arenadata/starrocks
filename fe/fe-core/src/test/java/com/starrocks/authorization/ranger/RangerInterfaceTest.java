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
package com.starrocks.authorization.ranger;

import com.google.common.collect.Lists;
import com.starrocks.analysis.ArithmeticExpr;
import com.starrocks.analysis.BinaryPredicate;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.NullLiteral;
import com.starrocks.analysis.TableName;
import com.starrocks.authorization.AccessControlProvider;
import com.starrocks.authorization.AccessController;
import com.starrocks.authorization.AccessDeniedException;
import com.starrocks.authorization.NativeAccessController;
import com.starrocks.authorization.PrivilegeType;
import com.starrocks.authorization.ranger.hive.RangerHiveAccessController;
import com.starrocks.authorization.ranger.hive.RangerHiveResource;
import com.starrocks.authorization.ranger.starrocks.RangerStarRocksAccessController;
import com.starrocks.authorization.ranger.starrocks.RangerStarRocksResource;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.InternalCatalog;
import com.starrocks.catalog.Type;
import com.starrocks.common.ErrorReportException;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.analyzer.Authorizer;
import com.starrocks.sql.ast.AstTraverser;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.Relation;
import com.starrocks.sql.ast.SelectRelation;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.SubqueryRelation;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.sql.parser.SqlParser;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Mock;
import mockit.MockUp;
import org.apache.ranger.audit.model.AuthzAuditEvent;
import org.apache.ranger.authorization.utils.JsonUtils;
import org.apache.ranger.plugin.audit.RangerDefaultAuditHandler;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.policyengine.RangerAccessResultProcessor;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngine;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class RangerInterfaceTest {
    static ConnectContext connectContext;
    static StarRocksAssert starRocksAssert;

    @BeforeAll
    public static void beforeClass() throws Exception {
        new MockUp<RangerBasePlugin>() {
            @Mock
            void init() {
            }

            @Mock
            RangerAccessResult evalDataMaskPolicies(RangerAccessRequest request, RangerAccessResultProcessor resultProcessor) {
                return null;
            }
        };

        UtFrameUtils.createMinStarRocksCluster();
        connectContext = UtFrameUtils.createDefaultCtx();
        starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.withDatabase("db").useDatabase("db").withTable("CREATE TABLE `t1` (\n" +
                "  `v4` bigint NULL COMMENT \"\",\n" +
                "  `v5` bigint NULL COMMENT \"\",\n" +
                "  `v6` bigint NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`v4`, `v5`, v6)\n" +
                "DISTRIBUTED BY HASH(`v4`) BUCKETS 3\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"in_memory\" = \"false\"\n" +
                ");");
    }

    @Test
    public void testMaskingNull() {
        new MockUp<RangerBasePlugin>() {
            @Mock
            RangerAccessResult evalDataMaskPolicies(RangerAccessRequest request, RangerAccessResultProcessor resultProcessor) {
                return null;
            }
        };
        ConnectContext connectContext = new ConnectContext();
        connectContext.setCurrentUserIdentity(UserIdentity.ROOT);
        TableName tableName = new TableName("db", "tbl");
        List<Column> columns = Lists.newArrayList(new Column("v1", Type.INT));

        RangerStarRocksAccessController connectScheduler = new RangerStarRocksAccessController();
        try {
            connectScheduler.getColumnMaskingPolicy(connectContext, tableName, columns);
        } catch (Exception e) {
            Assertions.fail();
        }
    }

    @Test
    public void testAccessControlProvider() {
        AccessControlProvider accessControlProvider = new AccessControlProvider(null,
                new NativeAccessController());
        accessControlProvider.removeAccessControl("hive");

        accessControlProvider.setAccessControl("hive", new NativeAccessController());
        accessControlProvider.setAccessControl("hive", new RangerStarRocksAccessController());
        Assertions.assertTrue(accessControlProvider.getAccessControlOrDefault("hive")
                instanceof RangerStarRocksAccessController);
        accessControlProvider.removeAccessControl("hive");

        Assertions.assertTrue(accessControlProvider.getAccessControlOrDefault("hive")
                instanceof NativeAccessController);

        accessControlProvider.setAccessControl("hive", new RangerStarRocksAccessController());
        Assertions.assertTrue(accessControlProvider.getAccessControlOrDefault("hive")
                instanceof RangerStarRocksAccessController);
        accessControlProvider.setAccessControl("hive", new NativeAccessController());
        Assertions.assertTrue(accessControlProvider.getAccessControlOrDefault("hive")
                instanceof NativeAccessController);
    }

    @Test
    public void testMaskingExpr() {
        new MockUp<RangerBasePlugin>() {
            @Mock
            RangerAccessResult evalDataMaskPolicies(RangerAccessRequest request, RangerAccessResultProcessor resultProcessor) {
                RangerAccessResult result = new RangerAccessResult(1, "starrocks",
                        new RangerServiceDef(), new RangerAccessRequestImpl());
                result.setMaskType(RangerPolicy.MASK_TYPE_NULL);
                return result;
            }
        };

        RangerStarRocksAccessController rangerStarRocksAccessController = new RangerStarRocksAccessController();

        ConnectContext connectContext = new ConnectContext();
        connectContext.setCurrentUserIdentity(UserIdentity.ROOT);
        TableName tableName = new TableName("db", "tbl");
        List<Column> columns = Lists.newArrayList(new Column("v1", Type.INT));

        Map<String, Expr> e = rangerStarRocksAccessController.getColumnMaskingPolicy(connectContext, tableName, columns);
        Assertions.assertTrue(new ArrayList<>(e.values()).get(0) instanceof NullLiteral);

        new MockUp<RangerBasePlugin>() {
            @Mock
            RangerAccessResult evalDataMaskPolicies(RangerAccessRequest request, RangerAccessResultProcessor resultProcessor) {
                RangerAccessResult result = new RangerAccessResult(1, "starrocks",
                        new RangerServiceDef(), new RangerAccessRequestImpl());
                result.setMaskType(RangerPolicy.MASK_TYPE_CUSTOM);
                result.setMaskedValue("v + 1");
                return result;
            }
        };

        e = rangerStarRocksAccessController.getColumnMaskingPolicy(connectContext, tableName, columns);
        Assertions.assertTrue(new ArrayList<>(e.values()).get(0) instanceof ArithmeticExpr);
    }

    @Test
    public void testRowAccessExpr() {
        new MockUp<RangerBasePlugin>() {
            @Mock
            RangerAccessResult evalRowFilterPolicies(RangerAccessRequest request, RangerAccessResultProcessor resultProcessor) {
                RangerAccessResult result = new RangerAccessResult(1, "starrocks",
                        new RangerServiceDef(), new RangerAccessRequestImpl());
                result.setFilterExpr("v1 = 1");
                return result;
            }
        };
        RangerStarRocksAccessController rangerStarRocksAccessController = new RangerStarRocksAccessController();

        ConnectContext connectContext = new ConnectContext();
        connectContext.setCurrentUserIdentity(UserIdentity.ROOT);
        TableName tableName = new TableName("db", "tbl");

        Expr rowFilter = rangerStarRocksAccessController.getRowAccessPolicy(connectContext, tableName);
        Assertions.assertTrue(rowFilter instanceof BinaryPredicate);
    }

    @Test
    public void testPermission() {
        new MockUp<RangerBasePlugin>() {
            @Mock
            RangerAccessResult isAccessAllowed(RangerAccessRequest request) {
                RangerAccessResult result = new RangerAccessResult(1, "starrocks",
                        new RangerServiceDef(), new RangerAccessRequestImpl());
                result.setIsAllowed(true);
                return result;
            }
        };
        RangerStarRocksAccessController rangerStarRocksAccessController = new RangerStarRocksAccessController();

        try {
            rangerStarRocksAccessController.hasPermission(
                    RangerStarRocksResource.builder().setSystem().build(), UserIdentity.ROOT, Set.of(), PrivilegeType.OPERATE);
        } catch (Exception e) {
            Assertions.fail();
        }

        new MockUp<RangerBasePlugin>() {
            @Mock
            RangerAccessResult isAccessAllowed(RangerAccessRequest request) {
                RangerAccessResult result = new RangerAccessResult(1, "starrocks",
                        new RangerServiceDef(), new RangerAccessRequestImpl());
                result.setIsAllowed(false);
                return result;
            }
        };

        Assertions.assertThrows(AccessDeniedException.class, () -> rangerStarRocksAccessController.hasPermission(
                RangerStarRocksResource.builder().setSystem().build(),
                new UserIdentity("alice", "%"), Set.of(), PrivilegeType.OPERATE));
    }

    @Test
    public void testRootUserBypassesRanger() {
        // Mock Ranger to deny all requests
        new MockUp<RangerBasePlugin>() {
            @Mock
            RangerAccessResult isAccessAllowed(RangerAccessRequest request) {
                RangerAccessResult result = new RangerAccessResult(1, "starrocks",
                        new RangerServiceDef(), new RangerAccessRequestImpl());
                result.setIsAllowed(false);
                return result;
            }
        };

        RangerStarRocksAccessController controller = new RangerStarRocksAccessController();

        // root user must bypass Ranger even when Ranger denies everything
        Assertions.assertDoesNotThrow(() -> controller.hasPermission(
                RangerStarRocksResource.builder().setSystem().build(),
                UserIdentity.ROOT, Set.of(), PrivilegeType.OPERATE));
        Assertions.assertDoesNotThrow(() -> controller.hasPermission(
                RangerStarRocksResource.builder().setCatalog("hive_catalog").build(),
                UserIdentity.ROOT, Set.of(), PrivilegeType.USAGE));
        Assertions.assertDoesNotThrow(() -> controller.hasPermission(
                RangerStarRocksResource.builder().setCatalog("default_catalog").setDatabase("db1").build(),
                UserIdentity.ROOT, Set.of(), PrivilegeType.CREATE_TABLE));
        Assertions.assertDoesNotThrow(() -> controller.hasPermission(
                RangerStarRocksResource.builder()
                        .setCatalog("default_catalog").setDatabase("db1").setTable("t1").build(),
                UserIdentity.ROOT, Set.of(), PrivilegeType.SELECT));

        // non-root user must still go through Ranger and be denied
        UserIdentity normalUser = new UserIdentity("alice", "%");
        Assertions.assertThrows(AccessDeniedException.class, () -> controller.hasPermission(
                RangerStarRocksResource.builder().setSystem().build(),
                normalUser, Set.of(), PrivilegeType.OPERATE));
    }

    @Test
    public void testAccessRequestCarriesStarRocksPrivilegeAsAction() throws Exception {
        List<RangerAccessRequest> requests = new ArrayList<>();
        new MockUp<RangerBasePlugin>() {
            @Mock
            RangerAccessResult isAccessAllowed(RangerAccessRequest request) {
                requests.add(request);
                RangerAccessResult result = new RangerAccessResult(0, "starrocks", new RangerServiceDef(), request);
                result.setIsAllowed(true);
                return result;
            }
        };
        UserIdentity user = new UserIdentity("alice", "%");

        RangerStarRocksAccessController starRocksController = new RangerStarRocksAccessController();
        starRocksController.hasPermission(RangerStarRocksResource.builder().setCatalog("default_catalog")
                .setDatabase("db").setTable("tbl").setColumn("id").build(), user, Set.of(), PrivilegeType.SELECT);
        starRocksController.hasPermission(RangerStarRocksResource.builder().setCatalog("default_catalog")
                .setDatabase("db").build(), user, Set.of(), PrivilegeType.CREATE_TABLE);
        starRocksController.hasPermission(RangerStarRocksResource.builder().setCatalog("default_catalog")
                .setDatabase("db").build(), user, Set.of(), PrivilegeType.ANY);

        RangerHiveAccessController hiveController = new RangerHiveAccessController("hive-service");
        hiveController.hasPermission(RangerHiveResource.builder().setDatabase("db").setTable("tbl").build(),
                user, Set.of(), PrivilegeType.INSERT);

        Assertions.assertEquals(4, requests.size());
        assertAccessTypeAndAction(requests.get(0), "select", "SELECT");
        assertAccessTypeAndAction(requests.get(1), "create table", "CREATE TABLE");
        assertAccessTypeAndAction(requests.get(2), RangerPolicyEngine.ANY_ACCESS, "ANY");
        assertAccessTypeAndAction(requests.get(3), "update", "INSERT");
    }

    private static void assertAccessTypeAndAction(RangerAccessRequest request, String accessType, String action) {
        Assertions.assertEquals(accessType, request.getAccessType());
        Assertions.assertEquals(action, request.getAction());
    }

    /**
     * End-to-end check for ADH-9780: a SELECT by a non-root user goes through the StarRocks authorizer into a real
     * Ranger policy engine (no Ranger Admin, policies are pushed directly), first without a matching policy and then
     * with one. Both Ranger audit events must carry the StarRocks privilege in "Access Type".
     */
    @Test
    public void testSelectAuditEventsHaveAccessType() throws Exception {
        RangerServiceDef serviceDef = loadStarRocksServiceDef();
        List<AuthzAuditEvent> auditEvents = new ArrayList<>();

        RangerStarRocksAccessController rangerController = new RangerStarRocksAccessController();
        RangerBasePlugin plugin = rangerController.getRangerPlugin();
        plugin.setResultProcessor(new RangerDefaultAuditHandler() {
            @Override
            public void logAuthzAudit(AuthzAuditEvent auditEvent) {
                if (auditEvent != null) {
                    auditEvents.add(auditEvent);
                }
            }
        });
        // only another user's policy: the request reaches the policy engine and is denied
        plugin.setPolicies(servicePolicies(serviceDef, 1L, selectOnT1Policy(1L, "bob")));

        AccessControlProvider accessControlProvider = Authorizer.getInstance();
        AccessController nativeController =
                accessControlProvider.getAccessControlOrDefault(InternalCatalog.DEFAULT_INTERNAL_CATALOG_NAME);
        accessControlProvider.setAccessControl(InternalCatalog.DEFAULT_INTERNAL_CATALOG_NAME, rangerController);

        ConnectContext ctx = UtFrameUtils.createDefaultCtx();
        UserIdentity alice = new UserIdentity("alice", "%");
        ctx.setCurrentUserIdentity(alice);
        ctx.setQualifiedUser(alice.getUser());
        ctx.setCurrentRoleIds(new HashSet<>());
        try {
            String sql = "select v4 from db.t1";

            StatementBase deniedStmt = UtFrameUtils.parseStmtWithNewParser(sql, ctx);
            ErrorReportException e = Assertions.assertThrows(ErrorReportException.class,
                    () -> Authorizer.check(deniedStmt, ctx));
            Assertions.assertTrue(e.getMessage().contains("Access denied"), e.getMessage());

            plugin.setPolicies(servicePolicies(serviceDef, 2L,
                    selectOnT1Policy(1L, "bob"), selectOnT1Policy(2L, "alice")));

            StatementBase allowedStmt = UtFrameUtils.parseStmtWithNewParser(sql, ctx);
            Assertions.assertDoesNotThrow(() -> Authorizer.check(allowedStmt, ctx));
        } finally {
            accessControlProvider.setAccessControl(InternalCatalog.DEFAULT_INTERNAL_CATALOG_NAME, nativeController);
            connectContext.setThreadLocalInfo();
        }

        List<AuthzAuditEvent> columnEvents = auditEvents.stream()
                .filter(event -> "default_catalog/db/t1/v4".equals(event.getResourcePath()))
                .collect(Collectors.toList());
        Assertions.assertEquals(2, columnEvents.size(), auditEvents.toString());
        assertSelectAuditEvent(columnEvents.get(0), 0, -1L);
        assertSelectAuditEvent(columnEvents.get(1), 1, 2L);
        for (AuthzAuditEvent event : auditEvents) {
            Assertions.assertNotNull(event.getAccessType(), event.toString());
        }
    }

    private static void assertSelectAuditEvent(AuthzAuditEvent event, int accessResult, long policyId) {
        Assertions.assertEquals("alice", event.getUser(), event.toString());
        // shown as "Access Type" in Ranger Admin, was null before ADH-9780
        Assertions.assertEquals("SELECT", event.getAccessType(), event.toString());
        // shown as "Permission" in Ranger Admin
        Assertions.assertEquals("select", event.getAction(), event.toString());
        Assertions.assertEquals(accessResult, event.getAccessResult(), event.toString());
        Assertions.assertEquals(policyId, event.getPolicyId(), event.toString());
    }

    private static RangerServiceDef loadStarRocksServiceDef() throws Exception {
        // fe-core tests run with fe/fe-core as the working directory
        Path path = Paths.get("..", "..", "conf", "ranger", "ranger-servicedef-starrocks.json");
        Assertions.assertTrue(Files.exists(path), "service definition not found: " + path.toAbsolutePath());
        RangerServiceDef serviceDef = JsonUtils.jsonToObject(
                new String(Files.readAllBytes(path), StandardCharsets.UTF_8), RangerServiceDef.class);
        Assertions.assertNotNull(serviceDef, "cannot parse " + path.toAbsolutePath());
        return serviceDef;
    }

    private static ServicePolicies servicePolicies(RangerServiceDef serviceDef, long version,
                                                   RangerPolicy... policies) {
        ServicePolicies servicePolicies = new ServicePolicies();
        // must match ranger.plugin.starrocks.service.name in test resources/ranger-starrocks-security.xml
        servicePolicies.setServiceName("starrocks");
        servicePolicies.setServiceDef(serviceDef);
        servicePolicies.setPolicyVersion(version);
        servicePolicies.setPolicies(Lists.newArrayList(policies));
        // audit denied requests too, as the default audit filters of a Ranger service do
        servicePolicies.setAuditMode(RangerPolicyEngine.AUDIT_ALL);
        return servicePolicies;
    }

    private static RangerPolicy selectOnT1Policy(long id, String user) {
        Map<String, RangerPolicy.RangerPolicyResource> resources = new HashMap<>();
        resources.put("catalog", new RangerPolicy.RangerPolicyResource(InternalCatalog.DEFAULT_INTERNAL_CATALOG_NAME));
        resources.put("database", new RangerPolicy.RangerPolicyResource("db"));
        resources.put("table", new RangerPolicy.RangerPolicyResource("t1"));
        resources.put("column", new RangerPolicy.RangerPolicyResource("*"));

        RangerPolicy.RangerPolicyItem item = new RangerPolicy.RangerPolicyItem();
        item.setUsers(Lists.newArrayList(user));
        item.setAccesses(Lists.newArrayList(new RangerPolicy.RangerPolicyItemAccess("select", true)));

        RangerPolicy policy = new RangerPolicy();
        policy.setId(id);
        policy.setName("select_t1_" + user);
        policy.setService("starrocks");
        policy.setIsEnabled(true);
        policy.setIsAuditEnabled(true);
        policy.setResources(resources);
        policy.setPolicyItems(Lists.newArrayList(item));
        return policy;
    }

    @Test
    public void testRewriteWithAlias() throws Exception {
        Expr e = SqlParser.parseSqlToExpr("v4+1", new ConnectContext().getSessionVariable().getSqlMode());
        HashMap<String, Expr> exprHashMap = new HashMap<>();
        exprHashMap.put("v4", e);

        try (MockedStatic<Authorizer> authorizerMockedStatic = Mockito.mockStatic(Authorizer.class)) {
            authorizerMockedStatic.when(() -> Authorizer.getColumnMaskingPolicy(Mockito.any(),
                    Mockito.any(), Mockito.any())).thenReturn(exprHashMap);

            StatementBase stmt = com.starrocks.sql.parser.SqlParser.parse("select * from t1 t",
                    connectContext.getSessionVariable().getSqlMode()).get(0);
            //Build View SQL without Policy Rewrite
            new AstTraverser<Void, Void>() {
                @Override
                public Void visitRelation(Relation relation, Void context) {
                    relation.setNeedRewrittenByPolicy(true);
                    return null;
                }
            }.visit(stmt);
            com.starrocks.sql.analyzer.Analyzer.analyze(stmt, connectContext);

            QueryStatement queryStatement = (QueryStatement) stmt;
            Assertions.assertTrue(((SelectRelation) queryStatement.getQueryRelation()).getRelation() instanceof SubqueryRelation);
        }
    }

    @Test
    public void testHiveConvertToAccessTypeCreate() {
        RangerHiveAccessController controller = new RangerHiveAccessController("hive-service");
        Assertions.assertEquals("select", controller.convertToAccessType(PrivilegeType.SELECT));
        Assertions.assertEquals("update", controller.convertToAccessType(PrivilegeType.INSERT));
        Assertions.assertEquals("create", controller.convertToAccessType(PrivilegeType.CREATE_TABLE));
        Assertions.assertEquals("create", controller.convertToAccessType(PrivilegeType.CREATE_VIEW));
        Assertions.assertEquals("create", controller.convertToAccessType(PrivilegeType.CREATE_DATABASE));
        Assertions.assertEquals("refresh", controller.convertToAccessType(PrivilegeType.REFRESH));
        Assertions.assertEquals("drop", controller.convertToAccessType(PrivilegeType.DROP));
        Assertions.assertEquals("alter", controller.convertToAccessType(PrivilegeType.ALTER));
    }
}
