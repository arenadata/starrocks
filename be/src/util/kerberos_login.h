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

#pragma once

#include "common/status.h"

namespace starrocks {

// Logs the embedded JVM in to Kerberos from `kerberos_keytab`, so that libhdfs and the JNI
// connectors authenticate as `kerberos_principal` without an external ticket cache.
// No-op when both configs are empty.
Status login_kerberos_if_configured();

// Whether the keytab login is configured. Callers that pass a user name down to Hadoop use this
// to avoid turning the login into a proxy-user request.
bool is_kerberos_login_configured();

} // namespace starrocks
