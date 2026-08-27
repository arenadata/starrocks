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

#include "util/kerberos_login.h"

#include "common/config.h"
#include "jni.h"
#include "udf/java/java_udf.h"
#include "udf/java/utils.h"
#include "util/defer_op.h"

#define CHECK_JNI_EXCEPTION(env, message)                                                          \
    if (jthrowable thr = env->ExceptionOccurred(); thr) {                                          \
        std::string jni_error_message = JVMFunctionHelper::getInstance().dumpExceptionString(thr); \
        env->ExceptionClear();                                                                     \
        env->DeleteLocalRef(thr);                                                                  \
        return Status::InternalError(fmt::format("{}, error: {}", message, jni_error_message));    \
    }

namespace starrocks {

static const char* kKerberosLoginClassName = "com/starrocks/connector/hadoop/KerberosLogin";
static const char* kLoginMethodSignature = "(Ljava/lang/String;Ljava/lang/String;I)V";

static Status do_kerberos_login() {
    RETURN_IF_ERROR(detect_java_runtime());

    // check JNIEnv before calling JVMFunctionHelper::getInstance() to avoid crash
    if (getJNIEnv() == nullptr) {
        return Status::InternalError("get JNIEnv failed");
    }
    JNIEnv* env = JVMFunctionHelper::getInstance().getEnv();

    jclass login_cls = env->FindClass(kKerberosLoginClassName);
    CHECK_JNI_EXCEPTION(env, "find class KerberosLogin failed")
    LOCAL_REF_GUARD_ENV(env, login_cls);

    jmethodID login_method = env->GetStaticMethodID(login_cls, "login", kLoginMethodSignature);
    CHECK_JNI_EXCEPTION(env, "get method login failed")

    jstring principal = env->NewStringUTF(config::kerberos_principal.c_str());
    LOCAL_REF_GUARD_ENV(env, principal);
    jstring keytab = env->NewStringUTF(config::kerberos_keytab.c_str());
    LOCAL_REF_GUARD_ENV(env, keytab);

    env->CallStaticVoidMethod(login_cls, login_method, principal, keytab,
                              static_cast<jint>(config::kerberos_relogin_check_interval_second));
    CHECK_JNI_EXCEPTION(env, "kerberos login failed")

    return Status::OK();
}

bool is_kerberos_login_configured() {
    return !config::kerberos_principal.empty() && !config::kerberos_keytab.empty();
}

Status login_kerberos_if_configured() {
    if (config::kerberos_principal.empty() && config::kerberos_keytab.empty()) {
        return Status::OK();
    }
    if (config::kerberos_principal.empty() || config::kerberos_keytab.empty()) {
        return Status::InvalidArgument("kerberos_principal and kerberos_keytab must both be set");
    }
    return call_hdfs_scan_function_in_pthread(do_kerberos_login)->get_future().get();
}

} // namespace starrocks
