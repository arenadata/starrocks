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

import com.google.common.base.Strings;
import com.starrocks.common.Pair;
import com.starrocks.common.util.NetUtils;

import java.net.UnknownHostException;

/**
 * Address that identifies a cluster node: host plus edit_log_port for an FE, host plus heartbeat port for a CN.
 */
public record HostPort(String host, int port) {

    public HostPort {
        if (Strings.isNullOrEmpty(host)) {
            throw new IllegalArgumentException("host must not be empty");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("invalid port " + port + " for host " + host);
        }
    }

    public static HostPort of(Pair<String, Integer> pair) {
        return new HostPort(pair.first, pair.second);
    }

    /**
     * Parses "host:port". An IPv6 literal must be bracketed: "[::1]:9010".
     */
    public static HostPort parse(String value) {
        String s = value == null ? "" : value.trim();
        int sep = s.lastIndexOf(':');
        if (sep <= 0 || sep == s.length() - 1) {
            throw new IllegalArgumentException("expected host:port, got '" + value + "'");
        }
        String host = s.substring(0, sep);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int port;
        try {
            port = Integer.parseInt(s.substring(sep + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port in '" + value + "'");
        }
        return new HostPort(host, port);
    }

    public Pair<String, Integer> toPair() {
        return Pair.create(host, port);
    }

    /**
     * True when both addresses denote the same node even if one is written as an IP and the other as an FQDN.
     */
    public boolean sameNode(HostPort other) {
        return other != null && port == other.port && sameHost(host, other.host);
    }

    /**
     * True when both names denote the same host: equal strings, equal IPs, or names resolving to the same
     * address. Falls back to plain string comparison when a host cannot be resolved.
     */
    public static boolean sameHost(String a, String b) {
        if (Strings.isNullOrEmpty(a) || Strings.isNullOrEmpty(b)) {
            return false;
        }
        if (a.equals(b) || NetUtils.isSameIP(a, b)) {
            return true;
        }
        try {
            Pair<String, String> first = NetUtils.getIpAndFqdnByHost(a);
            Pair<String, String> second = NetUtils.getIpAndFqdnByHost(b);
            if (NetUtils.isSameIP(first.first, second.first)) {
                return true;
            }
            return !first.second.isEmpty() && first.second.equals(second.second);
        } catch (UnknownHostException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return NetUtils.getHostPortInAccessibleFormat(host, port);
    }
}
