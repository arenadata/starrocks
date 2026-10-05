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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.starrocks.common.Config;
import com.starrocks.ha.FrontendNodeType;
import com.starrocks.service.FrontendOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Selects and holds the membership provider named by fe_membership_provider.
 * Providers bundled with the FE are found on the class path; an external one is loaded from
 * STARROCKS_HOME/lib/membership/&lt;name&gt;/*.jar.
 */
public final class MembershipProviders {
    private static final Logger LOG = LogManager.getLogger(MembershipProviders.class);

    public static final String NONE = "none";
    public static final String INITIAL_STATE_EXISTING = "existing";
    public static final String INITIAL_STATE_NEW = "new";

    private static volatile MembershipProvider current;
    private static volatile MembershipContext context;

    private MembershipProviders() {
    }

    public static synchronized void init(String starRocksHome) throws MembershipException {
        String name = normalize(Config.fe_membership_provider);
        if (name.isEmpty() || name.equals(NONE)) {
            current = null;
            context = null;
            return;
        }
        if (Strings.isNullOrEmpty(Config.auth_token)) {
            throw new MembershipException("fe_membership_provider=" + name + " requires auth_token to be set in fe.conf");
        }
        FrontendNodeType role = parseRole(Config.fe_membership_role);
        validateInitialState(Config.fe_cluster_initial_state);

        MembershipContext ctx = new MembershipContext(
                new HostPort(FrontendOptions.getLocalHostAddress(), Config.edit_log_port), role, FrontendOptions.isUseFqdn());
        MembershipProvider provider = lookup(name, starRocksHome);
        provider.start(ctx);
        current = provider;
        context = ctx;
        LOG.info("membership provider {} started: self {}, role {}, initial state {}",
                name, ctx.self(), role, normalize(Config.fe_cluster_initial_state));
    }

    private static MembershipProvider lookup(String name, String starRocksHome) throws MembershipException {
        ClassLoader loader = MembershipProviders.class.getClassLoader();
        if (!Strings.isNullOrEmpty(starRocksHome)) {
            File dir = new File(starRocksHome, "lib/membership/" + name);
            File[] jars = dir.isDirectory() ? dir.listFiles((d, f) -> f.endsWith(".jar")) : null;
            if (jars != null && jars.length > 0) {
                List<URL> urls = new ArrayList<>();
                for (File jar : jars) {
                    try {
                        urls.add(jar.toURI().toURL());
                    } catch (MalformedURLException e) {
                        throw new MembershipException("bad provider jar path " + jar, e);
                    }
                }
                loader = new URLClassLoader(urls.toArray(new URL[0]), loader);
                LOG.info("loading membership provider {} from {}", name, dir);
            }
        }
        for (MembershipProvider provider : ServiceLoader.load(MembershipProvider.class, loader)) {
            if (name.equals(normalize(provider.name()))) {
                return provider;
            }
        }
        throw new MembershipException("unknown fe_membership_provider '" + name + "'");
    }

    static FrontendNodeType parseRole(String value) throws MembershipException {
        try {
            return FrontendSpec.parseRole(value);
        } catch (IllegalArgumentException e) {
            throw new MembershipException("fe_membership_role: " + e.getMessage());
        }
    }

    static void validateInitialState(String value) throws MembershipException {
        String state = normalize(value);
        if (!state.equals(INITIAL_STATE_EXISTING) && !state.equals(INITIAL_STATE_NEW)) {
            throw new MembershipException("fe_cluster_initial_state must be existing or new, got '" + value + "'");
        }
    }

    private static String normalize(String value) {
        return Strings.nullToEmpty(value).trim().toLowerCase(Locale.ROOT);
    }

    public static Optional<MembershipProvider> current() {
        return Optional.ofNullable(current);
    }

    public static Optional<MembershipContext> context() {
        return Optional.ofNullable(context);
    }

    public static boolean isEnabled() {
        return current != null;
    }

    /** True when fe_cluster_initial_state allows the bootstrap candidate to create a new cluster. */
    public static boolean isInitialStateNew() {
        return normalize(Config.fe_cluster_initial_state).equals(INITIAL_STATE_NEW);
    }

    @VisibleForTesting
    public static synchronized void setForTest(MembershipProvider provider, MembershipContext ctx) {
        current = provider;
        context = ctx;
    }

    public static synchronized void close() {
        MembershipProvider provider = current;
        current = null;
        context = null;
        if (provider != null) {
            try {
                provider.close();
            } catch (Exception e) {
                LOG.warn("failed to close membership provider {}", provider.name(), e);
            }
        }
    }
}
