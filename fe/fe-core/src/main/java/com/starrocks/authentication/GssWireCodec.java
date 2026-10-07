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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Wire helpers for the MariaDB auth_gssapi client plugin. The auth switch data is
 * "SPN NUL mech" without a trailing NUL (the caller appends the final one), and
 * server-to-client tokens whose first byte is 0x00/0x01/0xFE/0xFF are prefixed with
 * 0x01, because clients read those bytes as OK/ERR/AuthSwitch packet headers.
 */
public final class GssWireCodec {
    private GssWireCodec() {
    }

    public static byte[] authSwitchData(String spn, String mech) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(spn.getBytes(StandardCharsets.UTF_8));
        out.write(0);
        out.writeBytes(mech.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    public static byte[] escapeServerToken(byte[] token) {
        if (token.length == 0) {
            return token;
        }
        int first = token[0] & 0xFF;
        if (first != 0x00 && first != 0x01 && first != 0xFE && first != 0xFF) {
            return token;
        }
        byte[] escaped = new byte[token.length + 1];
        escaped[0] = 0x01;
        System.arraycopy(token, 0, escaped, 1, token.length);
        return escaped;
    }
}
