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

When returning to the newer development version, take a fresh backup and follow that version's full upgrade procedure. Matching feature migrations do not eliminate unrelated schema changes between Dinky releases.
