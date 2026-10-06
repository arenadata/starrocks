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

package com.starrocks.http.rest;

import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.IAction;
import com.starrocks.membership.FakeMembershipProvider;
import com.starrocks.membership.MembershipProviders;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MembershipActionTest {

    @AfterEach
    public void tearDown() {
        MembershipProviders.setForTest(null, null);
    }

    @Test
    public void testActionsRegisteredOnlyWithProviderEnabled() throws Exception {
        ActionController controller = new ActionController();
        MembershipProviders.setForTest(null, null);
        MembershipAction.registerAction(controller);
        Assertions.assertNull(handler(controller, HttpMethod.GET, MembershipAction.LEADER_PATH));
        Assertions.assertNull(handler(controller, HttpMethod.POST, MembershipAction.JOIN_PATH));

        MembershipProviders.setForTest(new FakeMembershipProvider(), null);
        MembershipAction.registerAction(controller);
        Assertions.assertNotNull(handler(controller, HttpMethod.GET, MembershipAction.LEADER_PATH));
        Assertions.assertNotNull(handler(controller, HttpMethod.POST, MembershipAction.JOIN_PATH));
    }

    private static IAction handler(ActionController controller, HttpMethod method, String path) {
        BaseRequest request = new BaseRequest(null,
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path));
        return controller.getHandler(request);
    }
}
