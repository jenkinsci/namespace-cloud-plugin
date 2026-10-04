package io.jenkins.plugins.namespacecloud.client;

import com.google.protobuf.Timestamp;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.util.Secret;
import io.grpc.CallCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import java.io.Closeable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import namespace.cloud.compute.v1beta.Compute;
import namespace.cloud.compute.v1beta.ComputeServiceGrpc;
import namespace.cloud.registry.v1beta.ContainerRegistryServiceGrpc;
import namespace.cloud.registry.v1beta.Registry;

/**
 * Thin wrapper over the Namespace {@code ComputeService} gRPC API.
 *
 * <p>One instance owns one {@link ManagedChannel}; callers must {@link #close()}
 * it. The channel is cheap to keep open and expensive to rebuild, so the cloud
 * holds a long-lived client rather than creating one per provisioning request.
 */
public final class NamespaceClient implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(NamespaceClient.class.getName());

    /**
     * ComputeService is served per-region, not globally. {@code global.namespaceapis.com}
     * returns an nginx 404 for every compute route, so the region matters.
     */
    public static String computeEndpointForRegion(@NonNull String region) {
        return region.trim().toLowerCase(java.util.Locale.ROOT) + ".compute.namespaceapis.com";
    }

    public static final String DEFAULT_REGION = "us";

    /** ContainerRegistryService is global, not regional. */
    public static final String REGISTRY_ENDPOINT = "global.namespaceapis.com";

    /** Host prefix for images in a workspace's own registry. */
    public static final String REGISTRY_HOST = "nscr.io";

    private static final int PORT = 443;

    private final ManagedChannel computeChannel;
    private final CallCredentials credentials;
    private final String computeEndpoint;
    // Built on demand: only the configuration page lists images, so a client
    // used purely for provisioning never opens this connection.
    private volatile ManagedChannel registryChannel;

    public NamespaceClient(@NonNull String computeEndpoint, @NonNull Secret token) {
        this.computeEndpoint = computeEndpoint;
        this.computeChannel = newChannel(computeEndpoint);
        this.credentials = new BearerTokenCredentials(token);
    }

    private static ManagedChannel newChannel(String host) {
        return Grpc.newChannelBuilderForAddress(host, PORT, TlsChannelCredentials.create())
                .userAgent("jenkins-namespace-cloud")
                .keepAliveTime(60, TimeUnit.SECONDS)
                // gRPC does not read Jenkins' proxy settings by itself, and a
                // controller with no direct route out cannot reach Namespace
                // without them.
                .proxyDetector(new JenkinsProxyDetector())
                .build();
    }

    public String endpoint() {
        return computeEndpoint;
    }

    private ComputeServiceGrpc.ComputeServiceBlockingStub compute(Duration deadline) {
        return ComputeServiceGrpc.newBlockingStub(computeChannel)
                .withCallCredentials(credentials)
                .withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Read-only probe used by the configuration page's "Test connection" button.
     * Requires only {@code instance:list}, so it is safe to run from a
     * least-privilege token and creates nothing.
     *
     * @return the number of instances currently visible to the token.
     */
    public int listInstanceCount() {
        Compute.ListInstancesResponse resp = compute(Duration.ofSeconds(20))
                .listInstances(Compute.ListInstancesRequest.newBuilder()
                        .setMaxEntries(1)
                        .build());
        return resp.getInstancesCount();
    }

    /** Creates an instance. Returns immediately; the instance is not yet RUNNING. */
    public Compute.InstanceMetadata createInstance(@NonNull Compute.CreateInstanceRequest request) {
        Compute.DescribeInstanceResponse resp = compute(Duration.ofMinutes(2)).createInstance(request);
        return resp.getMetadata();
    }

    /**
     * Blocks until the instance reaches RUNNING.
     *
     * <p>Uses the unary {@code WaitInstanceSync} rather than the streaming
     * {@code WaitInstance}: the plugin has no use for intermediate state
     * transitions, and a unary call carries a deadline cleanly.
     */
    public Compute.InstanceMetadata waitForRunning(@NonNull String instanceId, @NonNull Duration timeout) {
        Compute.WaitInstanceResponse resp = compute(timeout)
                .waitInstanceSync(Compute.WaitInstanceRequest.newBuilder()
                        .setInstanceId(instanceId)
                        .build());
        return resp.getMetadata();
    }

    public Compute.InstanceMetadata describe(@NonNull String instanceId) {
        return compute(Duration.ofSeconds(30))
                .describeInstance(Compute.DescribeInstanceRequest.newBuilder()
                        .setInstanceId(instanceId)
                        .build())
                .getMetadata();
    }

    /** Destroys an instance. Treats "already gone" as success. */
    public void destroyInstance(@NonNull String instanceId, @NonNull String reason) {
        try {
            compute(Duration.ofSeconds(60))
                    .destroyInstance(Compute.DestroyInstanceRequest.newBuilder()
                            .setInstanceId(instanceId)
                            .setReason(reason)
                            .build());
        } catch (io.grpc.StatusRuntimeException e) {
            if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                LOGGER.log(Level.FINE, "Instance {0} was already destroyed", instanceId);
                return;
            }
            throw e;
        }
    }

    /**
     * Lists instances carrying the given label, used to reap instances whose
     * Jenkins-side node disappeared (controller restart, crash, manual delete).
     */
    public List<Compute.InstanceMetadata> listByLabel(@NonNull String key, @NonNull String value) {
        Compute.ListInstancesRequest req = Compute.ListInstancesRequest.newBuilder()
                .setMaxEntries(500)
                .addLabelFilter(namespace.stdlib.Labels.LabelFilterEntry.newBuilder()
                        .setName(key)
                        .setValue(value)
                        .setOp(namespace.stdlib.Labels.LabelFilterEntry.LabelFilterOp.EQUAL)
                        .build())
                .build();
        return compute(Duration.ofSeconds(30)).listInstances(req).getInstancesList();
    }

    /** Fetches the SSH endpoint for an instance; only used by the SSH launch strategy. */
    public Compute.GetSSHConfigResponse sshConfig(@NonNull String instanceId) {
        return compute(Duration.ofSeconds(30))
                .getSSHConfig(Compute.GetSSHConfigRequest.newBuilder()
                        .setInstanceId(instanceId)
                        .build());
    }

    /**
     * An id that cannot exist. Used to probe permissions: the API checks
     * authorization before it looks the instance up, so a non-existent id
     * separates "you may not do this" from "there is nothing to do it to"
     * without touching a real instance.
     */
    private static final String NONEXISTENT_INSTANCE_ID = "0000000000000";

    /** Which of the actions the plugin needs this token actually carries. */
    // "wait" cannot be a record component name; it would clash with Object.wait().
    public record Permissions(boolean canList, boolean canGet, boolean canWait, boolean canDestroy) {
        public boolean allGranted() {
            return canList && canGet && canWait && canDestroy;
        }

        public List<String> missing() {
            List<String> out = new java.util.ArrayList<>();
            if (!canList) {
                out.add("list");
            }
            if (!canGet) {
                out.add("get");
            }
            if (!canWait) {
                out.add("wait");
            }
            if (!canDestroy) {
                out.add("destroy");
            }
            return out;
        }
    }

    /**
     * Establishes which instance actions the token permits, without creating
     * anything.
     *
     * <p>Every check but {@code list} runs against a non-existent instance id.
     * Any status other than PERMISSION_DENIED means authorization passed, so
     * NOT_FOUND (and even INVALID_ARGUMENT, if the id shape is rejected) both
     * count as "granted". {@code create} cannot be probed this way and is the
     * one action that only shows up at provisioning time.
     */
    public Permissions probePermissions() {
        return new Permissions(
                probe(this::listInstanceCount),
                probe(() -> describe(NONEXISTENT_INSTANCE_ID)),
                probe(() -> waitForRunning(NONEXISTENT_INSTANCE_ID, Duration.ofSeconds(15))),
                probe(() -> {
                    destroyInstance(NONEXISTENT_INSTANCE_ID, "permission preflight");
                    return null;
                }));
    }

    private static boolean probe(java.util.concurrent.Callable<?> call) {
        try {
            call.call();
            return true;
        } catch (io.grpc.StatusRuntimeException e) {
            io.grpc.Status.Code code = e.getStatus().getCode();
            // An invalid, expired or revoked token fails EVERY call with
            // UNAUTHENTICATED. Treating that as "not a permission problem"
            // would report a placeholder token as fully granted, so it has to
            // propagate rather than be swallowed here.
            if (code == io.grpc.Status.Code.UNAUTHENTICATED) {
                throw e;
            }
            // Only an authz failure proves the grant is absent; NOT_FOUND and
            // friends mean the call got past authorization.
            return code != io.grpc.Status.Code.PERMISSION_DENIED;
        } catch (Exception e) {
            return true;
        }
    }

    private synchronized ManagedChannel registryChannel() {
        if (registryChannel == null) {
            registryChannel = newChannel(REGISTRY_ENDPOINT);
        }
        return registryChannel;
    }

    /**
     * Lists the images available in the workspace's own registry, as fully
     * qualified references ready to be used as an agent image.
     *
     * <p>Namespace reports a repository name; whether that already includes the
     * workspace segment varies, so a name that already looks like a path is
     * used as-is and a bare name is left bare. Either way the caller offers
     * these as suggestions, not as the only permitted values.
     */
    public List<String> listImageRefs(int maxRepositories) {
        return listImageRefs(maxRepositories, "");
    }

    /**
     * @param workspacePrefix the workspace segment of an nscr.io reference; the
     *     registry reports bare repository names, so without this the produced
     *     references are not pullable.
     */
    public List<String> listImageRefs(int maxRepositories, String workspacePrefix) {
        ContainerRegistryServiceGrpc.ContainerRegistryServiceBlockingStub stub =
                ContainerRegistryServiceGrpc.newBlockingStub(registryChannel())
                        .withCallCredentials(credentials)
                        .withDeadlineAfter(30, TimeUnit.SECONDS);

        List<String> refs = new java.util.ArrayList<>();
        Registry.ListRepositoriesResponse repos = stub.listRepositories(Registry.ListRepositoriesRequest.newBuilder()
                .setMaxEntries(maxRepositories)
                .build());

        for (Registry.Repository repo : repos.getRepositoriesList()) {
            String name = repo.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                Registry.ListTagsResponse tags = stub.listTags(Registry.ListTagsRequest.newBuilder()
                        .setRepository(name)
                        .setMaxEntries(50)
                        .build());
                String prefix =
                        workspacePrefix == null || workspacePrefix.isBlank() ? "" : workspacePrefix.trim() + "/";
                for (Registry.Tag t : tags.getTagsList()) {
                    if (!t.getTag().isBlank()) {
                        refs.add(REGISTRY_HOST + "/" + prefix + name + ":" + t.getTag());
                    }
                }
            } catch (io.grpc.StatusRuntimeException e) {
                // One unreadable repository should not blank the whole list.
                LOGGER.log(Level.FINE, e, () -> "Could not list tags for repository " + name);
            }
        }
        refs.sort(String::compareTo);
        return refs;
    }

    /** Outcome of checking a configured workspace prefix against the registry. */
    public enum PrefixCheck {
        /** The registry served this repository path, so the prefix is right. */
        VERIFIED,
        /** The registry refused the path; the prefix is almost certainly wrong. */
        REJECTED,
        /** No repositories to test against, or the registry could not be reached. */
        INCONCLUSIVE
    }

    /**
     * Checks a workspace prefix by asking the registry for a repository that is
     * known to exist.
     *
     * <p>The gRPC registry API cannot do this: it is tenant-scoped by the token
     * and never reports the tenant id, so a wrong prefix is indistinguishable
     * there. The Docker registry HTTP API can, because the prefix is part of the
     * path: {@code nscr.io/<prefix>/<repo>}.
     *
     * <p>Deliberately returns {@link PrefixCheck#INCONCLUSIVE} rather than
     * failing when anything is unexpected. A wrong answer here would block a
     * correct configuration, which is worse than not checking at all.
     */
    public PrefixCheck verifyWorkspacePrefix(@NonNull Secret token, @NonNull String prefix) {
        if (prefix.isBlank()) {
            return PrefixCheck.INCONCLUSIVE;
        }
        String repository;
        try {
            List<Registry.Repository> repos = ContainerRegistryServiceGrpc.newBlockingStub(registryChannel())
                    .withCallCredentials(credentials)
                    .withDeadlineAfter(20, TimeUnit.SECONDS)
                    .listRepositories(Registry.ListRepositoriesRequest.newBuilder()
                            .setMaxEntries(1)
                            .build())
                    .getRepositoriesList();
            if (repos.isEmpty()) {
                return PrefixCheck.INCONCLUSIVE;
            }
            repository = repos.get(0).getName();
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "Could not list repositories to verify the workspace prefix");
            return PrefixCheck.INCONCLUSIVE;
        }

        try {
            java.net.URL url = java.net
                    .URI
                    .create("https://" + REGISTRY_HOST + "/v2/" + prefix.trim() + "/" + repository + "/tags/list")
                    .toURL();
            // Through Jenkins' proxy, not around it; openConnection would
            // ignore the configuration and fail on a proxied controller.
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) hudson.ProxyConfiguration.open(url);
            c.setRequestMethod("GET");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            // nscr.io answers with Basic realm="registry"; the tenant token is
            // the password, as `nsc docker login` configures.
            String basic = java.util.Base64.getEncoder()
                    .encodeToString(
                            ("token:" + token.getPlainText()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            c.setRequestProperty("Authorization", "Basic " + basic);
            int code = c.getResponseCode();
            c.disconnect();
            if (code == 200) {
                return PrefixCheck.VERIFIED;
            }
            if (code == 401 || code == 403 || code == 404) {
                return PrefixCheck.REJECTED;
            }
            return PrefixCheck.INCONCLUSIVE;
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "Registry prefix check could not complete");
            return PrefixCheck.INCONCLUSIVE;
        }
    }

    /** Converts an absolute wall-clock deadline into the protobuf type the API expects. */
    public static Timestamp toTimestamp(@NonNull Instant when) {
        return Timestamp.newBuilder()
                .setSeconds(when.getEpochSecond())
                .setNanos(when.getNano())
                .build();
    }

    @Nullable
    public static Instant toInstant(@Nullable Timestamp ts) {
        if (ts == null || (ts.getSeconds() == 0 && ts.getNanos() == 0)) {
            return null;
        }
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }

    @Override
    public void close() {
        shutdown(computeChannel);
        ManagedChannel reg = registryChannel;
        if (reg != null) {
            shutdown(reg);
        }
    }

    private static void shutdown(ManagedChannel c) {
        c.shutdown();
        try {
            if (!c.awaitTermination(5, TimeUnit.SECONDS)) {
                c.shutdownNow();
            }
        } catch (InterruptedException e) {
            c.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
