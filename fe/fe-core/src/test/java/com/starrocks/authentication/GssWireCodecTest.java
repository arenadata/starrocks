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

package com.starrocks.authentication;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

public class GssWireCodecTest {
    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytesOf(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    public void testAuthSwitchDataSplitsSpnAndMechWithNuls() {
        Assertions.assertArrayEquals(bytes("starrocks/fe1@EXAMPLE.COM\0"),
                GssWireCodec.authSwitchData("starrocks/fe1@EXAMPLE.COM", ""));
        // no trailing NUL: MysqlProto appends the final one after the plugin data
        Assertions.assertArrayEquals(bytes("a@R\0Kerberos"), GssWireCodec.authSwitchData("a@R", "Kerberos"));
    }

    @Test
    public void testEscapesOnlyDangerousLeadingBytes() {
        // 0x6E is the first byte of a raw Kerberos AP-REQ token: untouched
        Assertions.assertArrayEquals(bytesOf(0x6E, 0x01), GssWireCodec.escapeServerToken(bytesOf(0x6E, 0x01)));
        for (int b : new int[] {0x00, 0x01, 0xFE, 0xFF}) {
            Assertions.assertArrayEquals(bytesOf(0x01, b, 0x42),
                    GssWireCodec.escapeServerToken(bytesOf(b, 0x42)), "byte " + b);
        }
        Assertions.assertArrayEquals(new byte[0], GssWireCodec.escapeServerToken(new byte[0]));
    }
}
