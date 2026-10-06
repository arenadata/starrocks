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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.ha.FrontendNodeType;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.IllegalArgException;
import com.starrocks.membership.FrontendSpec;
import com.starrocks.membership.HostPort;
import com.starrocks.membership.MembershipApi;
import com.starrocks.membership.MembershipException;
import com.starrocks.membership.MembershipJoinService;
import com.starrocks.membership.MembershipJoinService.ComputeNodeJoinResult;
import com.starrocks.membership.MembershipJoinService.FrontendJoinResult;
import com.starrocks.membership.MembershipJoinService.JoinException;
import com.starrocks.membership.MembershipProvider;
import com.starrocks.membership.MembershipProviders;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.NodeMgr;
import com.starrocks.server.RunMode;
import com.starrocks.system.Frontend;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.IOException;
import java.util.Locale;

/**
 * Membership API used by FEs and CNs that join the cluster without ALTER SYSTEM ADD.
 * <pre>
 * GET  /api/v2/membership/leader   leader address, cluster id and run mode; no auth
 * POST /api/v2/membership/join     registers the calling node; header token = auth_token; leader only
 * </pre>
 */
public final class MembershipAction {
    public static final String LEADER_PATH = MembershipApi.LEADER_PATH;
    public static final String JOIN_PATH = MembershipApi.JOIN_PATH;

    public static final String TYPE_FRONTEND = MembershipApi.TYPE_FRONTEND;
    public static final String TYPE_COMPUTE_NODE = MembershipApi.TYPE_COMPUTE_NODE;

    private MembershipAction() {
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        if (!MembershipProviders.isEnabled()) {
            return;
        }
        controller.registerHandler(HttpMethod.GET, LEADER_PATH, new LeaderAction(controller));
        controller.registerHandler(HttpMethod.POST, JOIN_PATH, new JoinAction(controller));
    }

    public record LeaderInfo(@JsonProperty("leader") String leader,
                             @JsonProperty("leader_http_port") int leaderHttpPort,
                             @JsonProperty("cluster_id") String clusterId,
                             @JsonProperty("run_mode") String runMode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JoinRequest(@JsonProperty("type") String type,
                              @JsonProperty("host") String host,
                              @JsonProperty("port") int port,
                              @JsonProperty("role") String role,
                              @JsonProperty("warehouse") String warehouse,
                              @JsonProperty("cngroup") String cnGroup) {
    }

    public record FrontendJoinResponse(@JsonProperty("type") String type,
                                       @JsonProperty("role") String role,
                                       @JsonProperty("node_name") String nodeName,
                                       @JsonProperty("helper") String helper,
                                       @JsonProperty("cluster_id") String clusterId,
                                       @JsonProperty("existed") boolean existed) {
    }

    public record ComputeNodeJoinResponse(@JsonProperty("type") String type,
                                          @JsonProperty("id") long id,
                                          @JsonProperty("warehouse") String warehouse,
                                          @JsonProperty("existed") boolean existed) {
    }

    abstract static class MembershipBaseAction extends RestBaseAction {
        MembershipBaseAction(ActionController controller) {
            super(controller);
        }

        protected void sendError(BaseRequest request, BaseResponse response, HttpResponseStatus status,
                                 String message) {
            sendResult(request, response, status, new RestBaseResult(message));
        }
    }

    /** Outcome of a join request's authorization. */
    enum Authorization {
        AUTHORIZED, UNAUTHORIZED, UNAVAILABLE
    }

    /**
     * The shared token authorizes; when it does not match, a provider that carries its own
     * membership proof may vouch for the exact node instead. A failing vouch check is
     * UNAVAILABLE, not UNAUTHORIZED: the caller should retry rather than give up.
     */
    static Authorization authorize(String token, String expectedToken, MembershipProvider provider, HostPort node) {
        if (!Strings.isNullOrEmpty(token) && token.equals(expectedToken)) {
            return Authorization.AUTHORIZED;
        }
        if (provider == null) {
            return Authorization.UNAUTHORIZED;
        }
        try {
            return provider.vouches(node) ? Authorization.AUTHORIZED : Authorization.UNAUTHORIZED;
        } catch (MembershipException e) {
            return Authorization.UNAVAILABLE;
        }
    }

    public static class LeaderAction extends MembershipBaseAction {
        public LeaderAction(ActionController controller) {
            super(controller);
        }

        @Override
        public void execute(BaseRequest request, BaseResponse response) {
            GlobalStateMgr globalStateMgr = GlobalStateMgr.getCurrentState();
            if (!globalStateMgr.isReady()) {
                sendError(request, response, HttpResponseStatus.SERVICE_UNAVAILABLE, "frontend is not ready");
                return;
            }
            NodeMgr nodeMgr = globalStateMgr.getNodeMgr();
            String leaderHost = nodeMgr.getLeaderIp();
            if (Strings.isNullOrEmpty(leaderHost)) {
                sendError(request, response, HttpResponseStatus.SERVICE_UNAVAILABLE, "no leader");
                return;
            }
            Frontend leader = nodeMgr.getFeByHost(leaderHost);
            HostPort leaderNode = leader != null
                    ? new HostPort(leader.getHost(), leader.getEditLogPort())
                    : new HostPort(leaderHost, Config.edit_log_port);
            sendResultByJson(request, response, new LeaderInfo(leaderNode.toString(),
                    nodeMgr.getLeaderIpAndHttpPort().second, String.valueOf(nodeMgr.getClusterId()), RunMode.name()));
        }
    }

    public static class JoinAction extends MembershipBaseAction {
        public JoinAction(ActionController controller) {
            super(controller);
        }

        @Override
        public void execute(BaseRequest request, BaseResponse response) throws DdlException {
            GlobalStateMgr globalStateMgr = GlobalStateMgr.getCurrentState();
            if (!globalStateMgr.isReady()) {
                sendError(request, response, HttpResponseStatus.SERVICE_UNAVAILABLE, "frontend is not ready");
                return;
            }
            if (redirectToLeader(request, response)) {
                return;
            }

            JoinRequest body;
            try {
                body = mapper.readValue(request.getContent(), JoinRequest.class);
            } catch (IOException e) {
                sendError(request, response, HttpResponseStatus.BAD_REQUEST, "invalid json body: " + e.getMessage());
                return;
            }
            HostPort node;
            try {
                node = new HostPort(body.host(), body.port());
            } catch (IllegalArgumentException e) {
                sendError(request, response, HttpResponseStatus.BAD_REQUEST, e.getMessage());
                return;
            }
            String clientHost = request.getHostString();
            if (!HostPort.sameHost(clientHost, node.host())) {
                sendError(request, response, HttpResponseStatus.FORBIDDEN,
                        "host " + node.host() + " does not match client address " + clientHost);
                return;
            }

            NodeMgr nodeMgr = globalStateMgr.getNodeMgr();
            Authorization authorization = authorize(request.getRequest().headers().get(MembershipApi.TOKEN_HEADER),
                    nodeMgr.getToken(), MembershipProviders.current().orElse(null), node);
            switch (authorization) {
                case AUTHORIZED -> {
                    // fall through to the registration below
                }
                case UNAVAILABLE -> {
                    sendError(request, response, HttpResponseStatus.SERVICE_UNAVAILABLE,
                            "cannot verify membership right now");
                    return;
                }
                default -> {
                    sendError(request, response, HttpResponseStatus.UNAUTHORIZED, "token mismatch");
                }
            }

            MembershipJoinService service = MembershipJoinService.forCurrentState();
            String type = Strings.nullToEmpty(body.type()).trim().toUpperCase(Locale.ROOT);
            try {
                switch (type) {
                    case TYPE_FRONTEND -> {
                        FrontendNodeType role;
                        try {
                            role = FrontendSpec.parseRole(body.role());
                        } catch (IllegalArgumentException e) {
                            sendError(request, response, HttpResponseStatus.BAD_REQUEST, e.getMessage());
                            return;
                        }
                        FrontendJoinResult result = service.joinFrontend(node, role);
                        sendResultByJson(request, response, new FrontendJoinResponse(TYPE_FRONTEND,
                                result.role().name(), result.nodeName(), result.helper().toString(),
                                result.clusterId(), result.existed()));
                    }
                    case TYPE_COMPUTE_NODE -> {
                        ComputeNodeJoinResult result = service.joinComputeNode(node, body.warehouse(), body.cnGroup());
                        sendResultByJson(request, response, new ComputeNodeJoinResponse(TYPE_COMPUTE_NODE,
                                result.id(), result.warehouse(), result.existed()));
                    }
                    default -> sendError(request, response, HttpResponseStatus.BAD_REQUEST,
                            "type must be " + TYPE_FRONTEND + " or " + TYPE_COMPUTE_NODE);
                }
            } catch (JoinException e) {
                sendError(request, response, HttpResponseStatus.valueOf(e.getStatus()), e.getMessage());
            }
        }
    }
}
