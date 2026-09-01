The building of Starrocks artifacts and packaging to runtime container images are performed in a hermetic, [multi-stage docker build](https://docs.docker.com/build/building/multi-stage/) environment. This setup enables the reuse of FE/BE artifacts for packaging into container images for different deployment scenarios. The building of artifacts will be executed in parallel leveraging the [BuildKit](https://docs.docker.com/build/buildkit/) for optimal speed.

![img.png](img.png)

### [1. StarRocks Ubuntu dev env image](dev-env/README.md)
### [2. StarRocks artifacts image](artifacts/README.md)
### [3. StarRocks fe image](fe/README.md)
### [4. StarRocks be image](be/README.md)
### [5. StarRocks all-in-one image](allin1/README.md)
### [6. StarRocks toolchains image](toolchains/README.md)

### Configuration and a read-only root filesystem

The `conf` directory of a component is meant to be replaced as a whole by the deployment: a ConfigMap or
Secret volume mounted at `/opt/starrocks/{fe,be,cn}/conf`, or a `docker run -v` bind mount. Nothing inside
the container writes into it, so the mount can be read-only and the container can run with
`readOnlyRootFilesystem: true`.

Because the mount replaces the directory, the deployment has to provide every configuration file it needs,
starting with `fe.conf` / `be.conf` / `cn.conf`. What the installation itself needs is kept out of `conf`:

* `bin/hadoop_env.sh` sets `HADOOP_CLASSPATH` and `HADOOP_USER_NAME`. A `conf/hadoop_env.sh` provided by
  the deployment is still sourced afterwards and can override anything it sets.
* `lib/default-conf/` holds the configuration files shipped with the image (`core-site.xml`, and
  `log4j2.properties` for BE/CN). It is on the classpath after `conf`, so a file of the same name provided
  in `conf` takes precedence.

What still has to be writable:

* the storage and log directories (`fe/meta`, `be/storage`, `cn/storage`, `*/log`);
* `/tmp` — `PID_DIR` defaults to it, and the JVM uses it as `java.io.tmpdir`. Point `UDF_RUNTIME_DIR` and
  the configuration entries that default into the installation directory (`spill_local_storage_dir`,
  `small_file_dir`, `user_function_dir`, FE `tmp_dir`) at a writable volume if you use those features.

`ADMIN SET FRONTEND CONFIG ... WITH PERSIST` is rejected inside a container, because the configuration file
belongs to the image or to the mounted volume and a persisted value would be lost on the next restart.
Change such a setting in the deployment's configuration and restart the FEs instead.
