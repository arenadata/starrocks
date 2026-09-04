#!/bin/bash
# Guards the contract the images rely on: nothing inside the container writes into the configuration
# directory, so that it can be mounted read-only (ConfigMap/Secret volume, readOnlyRootFilesystem).
# These checks are cheap to keep and catch a merge from upstream reintroducing the old behaviour.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/test_helpers.sh"

REPO_ROOT="$(cd "$DOCKERFILES_DIR/../.." && pwd)"

test_entrypoints_do_not_populate_conf_from_a_configmap()
{
    local f
    for f in "$DOCKERFILES_DIR"/fe/fe_entrypoint.sh \
             "$DOCKERFILES_DIR"/be/be_entrypoint.sh \
             "$DOCKERFILES_DIR"/be/cn_entrypoint.sh \
             "$DOCKERFILES_DIR"/common/entrypoint_common.sh ; do
        assert_file_not_contains "$f" "CONFIGMAP_MOUNT_PATH" \
            "$(basename "$f") copies the configuration into conf/ again"
        assert_file_not_contains "$f" "update_conf_from_configmap" \
            "$(basename "$f") copies the configuration into conf/ again"
    done
}

test_start_fe_does_not_rewrite_its_configuration_file()
{
    assert_file_not_contains "$REPO_ROOT/bin/start_fe.sh" "fe.conf.readonly" \
        "start_fe.sh replaces a read-only fe.conf with a writable copy again"
}

echo "installed scripts"
run_test test_entrypoints_do_not_populate_conf_from_a_configmap
run_test test_start_fe_does_not_rewrite_its_configuration_file
report_and_exit
