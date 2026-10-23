# Historical logs backport for Dinky 1.2.4

This branch starts at the official `v1.2.4` release tag, not the development branch. It selectively backports historical log collection, Agent/DaemonSet examples, JVM run identification, downloads, Operator status fallback and the related UI fixes. The Maven revision remains `1.2.4`.

The main development branch for this feature remains `feature/1.3.0-application-history-log`. Maintain this compatibility branch by selectively backporting reviewed fixes; do not merge the entire development branch into a company 1.2.4 installation.

## Version adaptations

- Preserve the existing one-argument `JobStatus.isTransition` API. Add the end-time-aware overload used by monitoring so explicit terminal evidence is distinguished from a temporary REST failure.
- Dinky 1.2.4 always assigns an owner to SQL resources. Retain this behavior while using the enclosing FlinkDeployment as owner for Operator Applications, so Deployment replacement does not remove SQL resources. Do not introduce a dependency on the newer owner-reference configuration switch.
- Keep migration filenames and SQL identical to the development feature branch. Their `1.3.x` filename suffixes identify migration versions, not a requirement to upgrade the application to Dinky 1.3.0. Renaming them would break migration identity when upgrading later.

## Deployment and upgrade

Back up the company database and test with a separate copy and an isolated Dinky instance first. Build all Dinky modules from this branch; do not mix 1.3.0 backend JARs with a 1.2.4 distribution. Use frontend assets built from this branch. Review all company-specific patches before combining them with this backport.

Use the migration scripts under `dinky-admin/src/main/resources/db/migration/<database>/` through the installation's existing migration process. Check schema-history entries and existing tables before applying anything manually; do not rerun initialization or downgrade scripts against an existing database. The new table and lifecycle column are additive.

Follow `README.md` for log mounts, Agent configuration and permissions. Historical logs are supported only for Kubernetes Application and Kubernetes Application Operator. Other modes hide the button. Without an Agent, recorded node and directory metadata remain available, but live file discovery and downloads are unavailable.

Before production use, verify both Application modes, job termination and restarts, multiple TaskManagers, rotated logs, download authorization, UID separators and the company's existing submission modes. Successful compilation and unit tests do not replace deployment validation.

## Backport validation

The Java 11 / Flink 1.18.1 Admin reactor build passed with 21 selected tests (log service, Operator status, SQL resource ownership, status transitions and shared archive configuration). The frontend production build and formatting checks for the historical log components passed. The old Spotless plugin could not resolve the Git worktree, so its check and Maven Enforcer were skipped; this is not a claim that all CI checks passed.

An isolated 1.2.4 container deployment with a fresh MySQL database was also verified: all seven migrations succeeded, a new Kubernetes Application JAR job reached RUNNING and completed checkpoints, and both JM/TM log downloads returned HTTP 200. The browser displayed the running job and its historical log rows. The deployed Admin JAR hash matched the local 1.2.4 build. This does not validate a company database upgrade, Operator deployment on this backport, or all failure/recovery cases.

## Runtime dependencies for a clean production distribution

The production package uses provided dependencies and needs runtime preparation. Supply the dependencies from this branch and its declared versions; do not fill gaps with Dinky development JARs:

- Place `mysql:mysql-connector-java:8.0.28` in the Dinky runtime classpath when using MySQL.
- Copy the built `dinky-client-hadoop-1.2.4.jar` and `dinky-client-base-1.2.4.jar` from their module `target` directories into `release/extends/`. Gateway SPI initialization loads YARN classes even when submitting a Kubernetes job.
- Supply the full `org.apache.flink:flink-table-planner_2.12:1.18.1` and its matching Scala runtime for Dinky's parser. The planner loader alone does not expose `ExtendedParser` to the application classloader. Resolve duplicate logging/HTTP dependencies explicitly; see the container README.
- Use `build/extends/dinky-app-1.18-1.2.4-jar-with-dependencies.jar` for the Flink-side Dinky launcher. The thin module JAR is insufficient. In 1.2.4 this launcher initializes JDBC access to the task database, so its Flink containers also require the JDBC driver and compatible planner/runtime libraries. Install them in the Flink image or explicit read-only mounts on every eligible node.
- If running alongside another Dinky, use separate container networking and a distinct web port. Dinky 1.2.4's print listener binds a fixed UDP port 7125; changing only the HTTP port while sharing host networking causes a collision. Configure kubeconfig with a Kubernetes API address reachable from inside the container rather than host loopback.

The validation cluster's very recent Kubernetes API returned additional `/version` fields that Flink 1.18.1's shaded client could not parse during the configuration availability probe. The actual Application submission and log download succeeded; do not treat that as proof that every Flink 1.18.1/Kubernetes version combination is supported. Check the company's Kubernetes version separately.

When returning to the newer development version, take a fresh backup and follow that version's full upgrade procedure. Matching feature migrations do not eliminate unrelated schema changes between Dinky releases.
