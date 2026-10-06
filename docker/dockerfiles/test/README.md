# Entrypoint script tests

Unit tests for the scripts that start FE/BE/CN inside the container images
(`docker/dockerfiles/{common,fe,be}/*.sh`).

Run them with:

```bash
./docker/dockerfiles/test/run_tests.sh
```

They need nothing but `bash` (3.2 is enough) and run in a couple of seconds: every test builds a
throw-away directory that mirrors the layout of the image and puts stubs for `mysql`, `nc`, `hostname`,
`timeout` and `start_{fe,be,cn}.sh` on the `PATH`, so no cluster and no StarRocks binary is involved.

What they cover:

* the process that ends up running: the entrypoints must `exec` into `start_{fe,be,cn}.sh` so that the
  container stop signal reaches the server and its exit status becomes the exit status of the container
  (BE keeps a restart loop for `DEBUG_MODE` / `COREDUMP_ENABLED` only);
* registering the node with the FE (`ALTER SYSTEM ADD FOLLOWER/BACKEND/COMPUTE NODE`), the ports read
  from the configuration file, `HOST_TYPE`, the FE leader probe and the warehouse of a compute node;
* that nothing is ever written into `$STARROCKS_HOME/conf`: it is mounted from a ConfigMap/Secret volume
  and the root filesystem may be read-only, so the configuration directory is used as it is found.

`stubs/` holds the fake commands, `lib/test_helpers.sh` the assertions and the fixture.

# Membership end-to-end test

`membership-e2e/` starts three FEs with docker compose and checks that they form one cluster through
the embedded membership provider (`fe_membership_provider = embedded` in `membership-e2e/fe.conf`):
no `ALTER SYSTEM ADD`, no `--helper`. It then restarts one FE and recreates another with empty meta,
and expects both to be alive members again.

```bash
./build.sh --fe
./docker/dockerfiles/test/membership-e2e/build-image.sh
./docker/dockerfiles/test/membership-e2e/run.sh
```

`STARROCKS_FE_IMAGE` selects another image, `TIMEOUT` (seconds, default 300) bounds every wait,
`KEEP=1` leaves the containers running. Container logs end up in `membership-e2e/logs.txt`.
