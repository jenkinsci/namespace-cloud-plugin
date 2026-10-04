# Jenkins — Namespace Cloud Agents

Provisions ephemeral Jenkins agents as Namespace micro-VM instances. Define
machine profiles once, give each a label, and any job asking for that label gets
a fresh instance for the build and nothing left running afterwards.

> **Community plugin.** Not affiliated with, endorsed by, or supported by
> Namespace Labs. It talks to their public API; issues with this plugin should
> come here, not to Namespace support.

## How it works

```
job needs label "ns-linux-8x16"
        │
        ▼
NamespaceCloud.provision()          matches the label to an AgentTemplate
        │
        ├─ register the Jenkins node (inbound only — the agent must be known
        │  before it dials in, or the controller rejects the connection)
        │
        ├─ ComputeService/CreateInstance
        │     shape      8 vCPU / 16 GB / amd64
        │     deadline   now + maxLifetime      ← server-side backstop
        │     labels     jenkins.io/cloud, jenkins.io/agent
        │     containers jenkins/inbound-agent:latest-jdk21
        │                JENKINS_URL / JENKINS_SECRET / JENKINS_AGENT_NAME
        │     volumes    cache volumes, reattached by tag across builds
        │
        ├─ ComputeService/WaitInstanceSync      until RUNNING
        │
        ▼
agent connects back over WebSocket, build runs
        │
        ▼
idle timeout → ComputeService/DestroyInstance
```

Three independent things stop instances leaking, in increasing order of
bluntness:

1. **Retention** — a single-executor profile uses `OnceRetentionStrategy`, which
   destroys the instance as soon as its build finishes. A profile with several
   executors uses `CloudRetentionStrategy` and terminates after N idle minutes,
   because ending it sooner would kill the builds on its other executors.
2. **Orphan reaper** — every 10 minutes, instances labelled for this controller
   whose Jenkins node no longer exists are destroyed (15-minute grace period so
   it never races provisioning).
3. **Instance deadline** — set on every `CreateInstance` call. Namespace enforces
   it server-side, so even a controller that crashes and never comes back cannot
   leak compute past that deadline.

## Requirements

- A Namespace workspace and a token (below)

## 1. Mint a scoped token

Grant only what the plugin uses. A token scoped this way cannot read your vault,
container registry, or anything else in the workspace.

```bash
nsc token create \
  --name jenkins \
  --expires_in 720h \
  --grant '{"resource_type":"instance","resource_id":"*","actions":["create","destroy","get","list","wait"]}' \
  --grant '{"resource_type":"instance/o11y/logs","resource_id":"*","actions":["get"]}'
```

> `resource_id` is not optional in practice. Omit it and the token can still
> `create` and `list` — those are not checked against a particular resource —
> but `get`, `wait` and `destroy` match nothing. The result is a token that
> creates instances it cannot then wait for or tear down, leaking them until
> their deadline expires.

| Resource             | Actions                          | Why                                              |
| -------------------- | -------------------------------- | ------------------------------------------------ |
| `instance`           | create, get, list, wait, destroy | Provision, poll to RUNNING, tear down            |
| `instance/o11y/logs` | get                              | Surface container logs in the agent log (optional)|

Only if a profile uses the **SSH** launcher, add:

```bash
  --grant '{"resource_type":"instance","resource_id":"*","actions":["ssh"]}' \
  --grant '{"resource_type":"ingress","resource_id":"*","actions":["access"]}'
```

The configuration page renders this exact command for your setup, so you do not
have to assemble the JSON by hand.

## 2. Configure Jenkins

1. Store the token as a **Secret text** credential.
2. *Manage Jenkins → Clouds → Add a new cloud → Namespace*.
3. Select the credential, leave the endpoint at `global.namespaceapis.com`, and
   press **Test connection**.

`Test connection` calls `ComputeService/ListInstances` — read-only, creates
nothing, and needs only `instance:list`. If the token is under-scoped, Namespace
returns a `PermissionDeniedError` naming each refused `resource_type`/`action`,
and the plugin reports them individually rather than a bare "permission denied".

> `TenantService/DescribePolicies` looks like the natural way to validate a
> token, but it requires an **admin**-scoped token — a correctly least-privileged
> Jenkins token would fail it. Hence the probe-and-decode approach.

4. Add one or more **agent profiles**. Each maps a Jenkins label to an instance
   shape and a launch method.

## 3. Use it from a job

```groovy
pipeline {
    agent { label 'ns-linux-8x16' }
    stages {
        stage('build') {
            steps { sh 'make -j$(nproc)' }
        }
    }
}
```

## Launch methods

| | Inbound (default) | SSH |
|---|---|---|
| Direction | instance → controller | controller → instance |
| Firewall | agent needs egress to the Jenkins URL | controller needs egress to Namespace ingress |
| Extra grants | none | `instance:ssh`, `ingress:access` |
| Key management | none | you supply a keypair |

Prefer **inbound** unless the agent genuinely cannot dial out. The controller's
Jenkins URL must be reachable from the instance; override it per-cloud if the
globally configured URL is internal-only.

Match the agent image's JDK to your controller's Java major version — the
default is `jenkins/inbound-agent:latest-jdk21`.

## Profile options worth knowing

- **Cache volumes** — `mountPoint:tag:sizeMb` per line, e.g.
  `/home/jenkins/.m2:maven:20480`. Instances sharing a tag reuse the same cache,
  which is most of the value of running on Namespace.
- **Expose Docker socket** — hands the instance's `dockerd` socket to the agent,
  so builds can use Docker without docker-in-docker.
- **Max lifetime** — the server-side deadline. Set it above your longest build.

## Building

```bash
mvn verify          # tests + spotbugs + builds target/namespace-cloud.hpi
mvn hpi:run         # http://localhost:8080/jenkins with the plugin loaded
```

Install `target/namespace-cloud.hpi` via *Manage Jenkins → Plugins → Advanced →
Deploy Plugin*.

### Protobuf stubs

Namespace's API is gRPC. Buf hosts the schema at
[buf.build/namespace/cloud](https://buf.build/namespace/cloud) but does **not**
publish prebuilt Java SDKs for it (the Maven registry returns 422), so the
stubs are generated at build time by `protobuf-maven-plugin` from the protos
vendored under `src/main/proto`. See `src/main/proto/VENDORED.txt` for the
upstream commit.

To refresh them:

```bash
curl -s -X POST https://buf.build/buf.registry.module.v1.DownloadService/Download \
  -H 'Content-Type: application/json' \
  -d '{"values":[{"resourceRef":{"name":{"owner":"namespace","module":"cloud"}}}]}'
```

and copy the transitive closure of `compute/v1beta/compute.proto` and
`iam/v1beta/authz.proto` (currently 7 files) back into `src/main/proto`.

## Licence

The plugin is MIT (see `LICENSE`).

`src/main/proto/` contains Namespace's API protocol buffer definitions,
redistributed under the Apache License 2.0 and unmodified. Namespace Labs
publishes the generated Go bindings for the same definitions under Apache-2.0 at
[namespacelabs/integrations](https://github.com/namespacelabs/integrations).
They are vendored because Buf publishes no prebuilt Java SDK for that module.
See `NOTICE` for full attribution.

## Status

Built, unit-tested, and packaged; the configuration forms are exercised by a
round-trip test. **The wire interaction with the live Namespace API has not been
run** — there were no workspace credentials available when this was written. The
request shapes are built against the published protos, but expect to shake out
details (region strings, SSH endpoint format, image entrypoint conventions) on
first contact with a real workspace.
