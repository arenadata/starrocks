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

package com.starrocks.credential;

import com.starrocks.common.Config;
import com.starrocks.connector.hadoop.HadoopExt;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.thrift.TCloudConfiguration;

import java.util.Map;

/**
 * Marks a cloud configuration sent to the BE as belonging to the user running the query, so that
 * the BE reaches external storage as that user instead of the cluster Kerberos principal.
 * <p>
 * The user also becomes part of the configuration string the BE uses as its file system cache key,
 * which keeps one user's file system from being handed to another.
 */
public class HadoopImpersonation {
    private HadoopImpersonation() {
    }

    /**
     * The user external storage should be accessed as, or null when the query runs as the cluster
     * principal.
     * <p>
     * Statistics collection, metadata collection and anything else running as the built-in root
     * user keep the cluster principal: those identities exist only inside StarRocks, and a Hadoop
     * cluster asked to let the principal proxy as `root` rejects the request.
     */
    public static String currentUser() {
        if (!Config.enable_hadoop_impersonation) {
            return null;
        }
        ConnectContext context = ConnectContext.get();
        if (context == null) {
            return null;
        }
        if (context.isStatisticsJob() || context.isStatisticsConnection() || context.isMetadataContext()) {
            return null;
        }
        UserIdentity userIdentity = context.getCurrentUserIdentity();
        if (userIdentity == null || UserIdentity.ROOT.equals(userIdentity)) {
            return null;
        }
        String user = userIdentity.getUser();
        return user == null || user.isEmpty() ? null : user;
    }

    /**
     * Applied after {@link CloudConfiguration#toThrift}, which subclasses override, so that every
     * cloud type keeps its own serialization.
     */
    public static void apply(TCloudConfiguration tCloudConfiguration) {
        String user = currentUser();
        if (user == null) {
            return;
        }

        Map<String, String> properties = tCloudConfiguration.getCloud_properties();
        if (properties == null) {
            return;
        }
        properties.put(HadoopExt.HADOOP_USERNAME, user);
        properties.put(HadoopExt.HADOOP_IMPERSONATION_ENABLED, "true");
        properties.computeIfPresent(HadoopExt.HADOOP_CLOUD_CONFIGURATION_STRING,
                (key, confString) -> confString + ", impersonated='" + user + "'");
    }
}
