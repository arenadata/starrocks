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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class HostPortTest {

    @Test
    public void testParse() {
        HostPort hp = HostPort.parse(" fe-1.example.org:9010 ");
        Assertions.assertEquals("fe-1.example.org", hp.host());
        Assertions.assertEquals(9010, hp.port());
        Assertions.assertEquals("fe-1.example.org:9010", hp.toString());

        HostPort v6 = HostPort.parse("[fe80::1]:9010");
        Assertions.assertEquals("fe80::1", v6.host());
        Assertions.assertEquals(9010, v6.port());
        Assertions.assertEquals("[fe80::1]:9010", v6.toString());
    }

    @Test
    public void testParseRejectsMalformed() {
        for (String bad : new String[] {"", "host", "host:", ":9010", "host:abc", "host:0", "host:70000"}) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> HostPort.parse(bad), bad);
        }
    }

    @Test
    public void testEqualityAndPair() {
        HostPort a = new HostPort("10.0.0.1", 9010);
        HostPort b = HostPort.of(a.toPair());
        Assertions.assertEquals(a, b);
        Assertions.assertEquals(a.hashCode(), b.hashCode());
        Assertions.assertNotEquals(a, new HostPort("10.0.0.1", 9011));
        Assertions.assertNotEquals(a, new HostPort("10.0.0.2", 9010));
    }

    @Test
    public void testSameNodeResolvesNames() {
        HostPort byName = new HostPort("localhost", 9010);
        HostPort byIp = new HostPort("127.0.0.1", 9010);
        Assertions.assertTrue(byName.sameNode(byIp));
        Assertions.assertTrue(byIp.sameNode(byName));
        Assertions.assertFalse(byName.sameNode(new HostPort("127.0.0.1", 9011)));
        Assertions.assertFalse(byName.sameNode(new HostPort("no-such-host.invalid", 9010)));
        Assertions.assertFalse(byName.sameNode(null));
    }
}
