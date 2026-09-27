package io.jenkins.plugins.namespacecloud;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.jenkins.plugins.namespacecloud.client.NamespaceClient;
import io.jenkins.plugins.namespacecloud.client.PermissionDiagnostics;
import io.jenkins.plugins.namespacecloud.client.RequiredGrants;
import io.jenkins.plugins.namespacecloud.launcher.AgentLaunchStrategy;
import io.jenkins.plugins.namespacecloud.launcher.LaunchContext;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import jenkins.slaves.JnlpAgentReceiver;
import namespace.cloud.compute.v1beta.Compute;
import namespace.stdlib.Labels;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.POST;

/**
 * A Jenkins cloud that provisions agents as Namespace instances.
 *
 * <p>Configured once globally (endpoint, token, profiles); jobs then select a
 * profile by label and Jenkins asks this cloud to provision when the queue
 * cannot be served by existing nodes.
 */
public class NamespaceCloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(NamespaceCloud.class.getName());

    /**
     * Instance labels, used to reap instances orphaned by a controller restart.
     *
     * <p>Namespace documents label names as matching
     * {@code ^[a-z]([a-z0-9-.]*[a-z0-9])?$} and at most 63 bytes. A Kubernetes-style
     * {@code jenkins.io/agent} is therefore rejected: the slash is not in that
     * character class, and CreateInstance fails with INVALID_ARGUMENT.
     */
    public static final String LABEL_MANAGED_BY = "jenkins-managed-by";

    public static final String LABEL_CLOUD = "jenkins-cloud";
    public static final String LABEL_AGENT = "jenkins-agent";
    public static final String MANAGED_BY_VALUE = "jenkins-namespace-cloud";

    private static final long PERMISSION_TTL_MILLIS = 300_000L;

    private String displayName;
    private String credentialsId;
    private String region = NamespaceClient.DEFAULT_REGION;
    private String computeEndpointOverride;
    private String instanceRegion = "";
    private String registryPrefix = "";
    private String jenkinsUrlOverride;
    private int instanceCap = 50;
    private List<AgentTemplate> templates = new ArrayList<>();

    private transient volatile NamespaceClient cachedClient;
    private transient volatile String cachedClientKey;
    private transient volatile NamespaceClient.Permissions cachedPermissions;
    private transient volatile long permissionsCheckedAt;
    private transient volatile String lastLoggedMissing;
    private transient volatile long lastMissingLoggedAt;

    @DataBoundConstructor
    public NamespaceCloud(@NonNull String name) {
        super(name);
    }

    /**
     * The label shown in the UI, which is safe to change at any time.
     *
     * <p>{@link #name} deliberately is not editable on the configuration page.
     * Jenkins asks for it when the cloud is created and rejects a duplicate
     * there, but nothing stops a later edit from colliding with another cloud,
     * and duplicate cloud names cause trouble inside Jenkins. Renaming is
     * therefore done here instead, leaving the identity alone.
     *
     * <p>Falls back to the name so the UI never shows a blank cloud.
     */
    @Override
    @NonNull
    public String getDisplayName() {
        return displayName == null || displayName.isBlank() ? name : displayName;
    }

    @DataBoundSetter
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
        invalidateClient();
    }

    public String getRegion() {
        return region == null || region.isBlank() ? NamespaceClient.DEFAULT_REGION : region.trim();
    }

    @DataBoundSetter
    public void setRegion(String region) {
        this.region = region;
        invalidateClient();
    }

    public String getComputeEndpointOverride() {
        return computeEndpointOverride;
    }

    @DataBoundSetter
    public void setComputeEndpointOverride(String computeEndpointOverride) {
        this.computeEndpointOverride = computeEndpointOverride;
        invalidateClient();
    }

    /**
     * The site to place the instance in, e.g. {@code ord}.
     *
     * <p>Distinct from {@link #getRegion()}, which only selects which API host
     * to talk to. Blank is the sensible default: Namespace then picks any site
     * with capacity for the requested shape. Setting this to an endpoint prefix
     * such as "us" matches no site and the API rejects the request with
     * {@code no available region to start a linux/amd64 instance}.
     */
    public String getInstanceRegion() {
        return instanceRegion == null ? "" : instanceRegion.trim();
    }

    @DataBoundSetter
    public void setInstanceRegion(String instanceRegion) {
        this.instanceRegion = instanceRegion;
    }

    /**
     * The workspace segment in nscr.io image references, e.g. {@code abc012abc012}
     * in {@code nscr.io/abc012abc012/jenkins-agent:1.0}.
     *
     * <p>It has to be configured: the registry API reports bare repository
     * names and the tenant is implicit in the token, and no RPC available to a
     * least-privileged token reveals the tenant id. Without it, suggested image
     * references are missing the segment and Namespace answers
     * {@code failed to resolve image}.
     */
    public String getRegistryPrefix() {
        return registryPrefix == null ? "" : registryPrefix.trim();
    }

    @DataBoundSetter
    public void setRegistryPrefix(String registryPrefix) {
        this.registryPrefix = registryPrefix;
    }

    /** ComputeService is regional; the override exists for non-standard deployments. */
    public String getComputeEndpoint() {
        if (computeEndpointOverride != null && !computeEndpointOverride.isBlank()) {
            return computeEndpointOverride.trim();
        }
        return NamespaceClient.computeEndpointForRegion(getRegion());
    }

    public String getJenkinsUrlOverride() {
        return jenkinsUrlOverride;
    }

    @DataBoundSetter
    public void setJenkinsUrlOverride(String jenkinsUrlOverride) {
        this.jenkinsUrlOverride = jenkinsUrlOverride;
    }

    public int getInstanceCap() {
        return instanceCap <= 0 ? Integer.MAX_VALUE : instanceCap;
    }

    @DataBoundSetter
    public void setInstanceCap(int instanceCap) {
        this.instanceCap = instanceCap;
    }

    @NonNull
    public List<AgentTemplate> getTemplates() {
        return templates == null ? Collections.emptyList() : templates;
    }

    @DataBoundSetter
    public void setTemplates(List<AgentTemplate> templates) {
        this.templates = templates == null ? new ArrayList<>() : new ArrayList<>(templates);
    }

    // ---------------------------------------------------------------- client

    private synchronized void invalidateClient() {
        if (cachedClient != null) {
            cachedClient.close();
            cachedClient = null;
        }
        cachedClientKey = null;
        cachedPermissions = null;
        permissionsCheckedAt = 0L;
        lastLoggedMissing = null;
        lastMissingLoggedAt = 0L;
    }

    /**
     * The instance actions this token carries, re-probed periodically.
     *
     * <p>Checked before provisioning because creating an instance we are not
     * allowed to destroy leaks paid compute until its deadline expires.
     */
    @NonNull
    private NamespaceClient.Permissions permissions() throws IOException {
        NamespaceClient.Permissions cached = cachedPermissions;
        if (cached != null && System.currentTimeMillis() - permissionsCheckedAt < PERMISSION_TTL_MILLIS) {
            return cached;
        }
        NamespaceClient.Permissions fresh = client().probePermissions();
        cachedPermissions = fresh;
        permissionsCheckedAt = System.currentTimeMillis();
        return fresh;
    }

    /**
     * Returns a client for the configured endpoint and token, rebuilding it if
     * either changed since last time.
     */
    @NonNull
    public synchronized NamespaceClient client() throws IOException {
        Secret token = resolveToken(credentialsId);
        if (token == null) {
            throw new IOException("No Namespace token configured for cloud \"" + name + "\".");
        }
        String key = getComputeEndpoint() + '|' + token.getEncryptedValue();
        if (cachedClient == null || !key.equals(cachedClientKey)) {
            if (cachedClient != null) {
                cachedClient.close();
            }
            cachedClient = new NamespaceClient(getComputeEndpoint(), token);
            cachedClientKey = key;
        }
        return cachedClient;
    }

    /** Public so the launch strategies can resolve the same credential for their own lookups. */
    @CheckForNull
    public static Secret resolveToken(@CheckForNull String credentialsId) {
        if (credentialsId == null || credentialsId.isBlank()) {
            return null;
        }
        StringCredentials c = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class, Jenkins.get(), ACL.SYSTEM2, Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId));
        return c == null ? null : c.getSecret();
    }

    public void destroyInstance(@NonNull String instanceId, @NonNull String reason) {
        try {
            client().destroyInstance(instanceId, reason);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ----------------------------------------------------------- provisioning

    @CheckForNull
    public AgentTemplate templateFor(@CheckForNull Label label) {
        for (AgentTemplate t : getTemplates()) {
            if (label == null ? t.getLabels().isBlank() : t.matches(label)) {
                return t;
            }
        }
        return null;
    }

    @Override
    public boolean canProvision(CloudState state) {
        return templateFor(state.getLabel()) != null;
    }

    /** Nodes this cloud currently owns, counted against the caps. */
    private long currentAgents(@Nullable AgentTemplate template) {
        return Jenkins.get().getNodes().stream()
                .filter(NamespaceAgent.class::isInstance)
                .map(NamespaceAgent.class::cast)
                .filter(a -> name.equals(a.getCloudName()))
                .filter(a -> template == null || template.getName().equals(a.getTemplateName()))
                .count();
    }

    @Override
    public Collection<PlannedNode> provision(CloudState state, int excessWorkload) {
        List<PlannedNode> planned = new ArrayList<>();
        AgentTemplate template = templateFor(state.getLabel());
        if (template == null) {
            return planned;
        }

        // Refuse to provision with a token that cannot tear down what it makes.
        // Without this the retry loop creates a fresh instance every few
        // seconds and abandons each one until its deadline.
        try {
            NamespaceClient.Permissions perms = permissions();
            if (!perms.allGranted()) {
                warnAboutMissingGrants(perms);
                return planned;
            }
            // Recovered: let the next failure log again immediately.
            lastLoggedMissing = null;
        } catch (IOException | RuntimeException e) {
            // Includes UNAUTHENTICATED: an invalid or expired token cannot be
            // used to provision, and must not be mistaken for a transient fault.
            LOGGER.log(Level.SEVERE, e, () -> "Not provisioning: cannot verify Namespace token for cloud " + name);
            return planned;
        }

        long globalHeadroom = getInstanceCap() - currentAgents(null);
        long templateHeadroom = template.getInstanceCap() - currentAgents(template);
        long headroom = Math.min(globalHeadroom, templateHeadroom);
        if (headroom <= 0) {
            LOGGER.log(Level.FINE, "Instance cap reached for cloud {0}; not provisioning.", name);
            return planned;
        }

        int executorsPerNode = template.getNumExecutors();
        int wanted = (int) Math.ceil(excessWorkload / (double) executorsPerNode);
        int toProvision = (int) Math.min(wanted, headroom);

        for (int i = 0; i < toProvision; i++) {
            String agentName =
                    template.getName() + "-" + UUID.randomUUID().toString().substring(0, 8);
            Callable<Node> task = () -> createAgent(template, agentName);
            planned.add(new PlannedNode(agentName, Computer.threadPoolForRemoting.submit(task), executorsPerNode));
        }
        LOGGER.log(Level.INFO, "Provisioning {0} Namespace agent(s) for label {1}", new Object[] {
            toProvision, state.getLabel()
        });
        return planned;
    }

    /**
     * Logs the missing-grant error, but not on every retry.
     *
     * <p>Jenkins re-asks the cloud to provision every ~10 seconds for as long as
     * work is queued. Repeating a multi-line error at that rate buries
     * everything else in the log, so this reports only when the set of missing
     * actions changes or the previous report has gone stale.
     */
    private void warnAboutMissingGrants(NamespaceClient.Permissions perms) {
        String missing = perms.missing().toString();
        long now = System.currentTimeMillis();
        boolean changed = !missing.equals(lastLoggedMissing);
        if (!changed && now - lastMissingLoggedAt < PERMISSION_TTL_MILLIS) {
            LOGGER.log(Level.FINE, "Still not provisioning; token for {0} missing {1}", new Object[] {name, missing});
            return;
        }
        lastLoggedMissing = missing;
        lastMissingLoggedAt = now;
        LOGGER.log(
                Level.SEVERE,
                "Not provisioning: the Namespace token for cloud \"{0}\" is missing instance actions {1}. "
                        + "Re-mint it with:\n{2}",
                new Object[] {name, missing, RequiredGrants.nscCommand(false)});
    }

    /**
     * Creates the instance and the matching Jenkins node.
     *
     * <p>For inbound agents the node is registered <em>before</em> the instance
     * is created: the agent dials in on its own, and the controller rejects a
     * connection for a node it does not yet know.
     */
    private Node createAgent(AgentTemplate template, String agentName) throws Exception {
        AgentLaunchStrategy strategy = template.getLaunchStrategy();
        String secret = JnlpAgentReceiver.SLAVE_SECRET.mac(agentName);
        LaunchContext ctx =
                new LaunchContext(agentName, secret, effectiveJenkinsUrl(), template.getRemoteFs(), template);

        Compute.CreateInstanceRequest request =
                buildRequest(template, agentName, ctx, strategy, template.toInstanceShape());

        NamespaceAgent agent = null;
        String instanceId = null;
        try {
            if (strategy.requiresNodeRegisteredFirst()) {
                // Placeholder launcher; replaced below once we have the instance.
                agent = new NamespaceAgent(
                        agentName,
                        template.getRemoteFs(),
                        strategy.createLauncher(client(), Compute.InstanceMetadata.getDefaultInstance(), ctx),
                        name,
                        "pending",
                        template);
                Jenkins.get().addNode(agent);
            }

            Compute.InstanceMetadata created = client().createInstance(request);
            instanceId = created.getInstanceId();
            LOGGER.log(
                    Level.INFO, "Created Namespace instance {0} for agent {1}", new Object[] {instanceId, agentName});

            Compute.InstanceMetadata running =
                    client().waitForRunning(instanceId, Duration.ofMinutes(Math.max(2, template.getIdleMinutes())));
            if (running.getStatus() == Compute.InstanceMetadata.Status.ERROR) {
                throw new IOException("Namespace instance " + instanceId + " failed to start");
            }

            NamespaceAgent finalAgent = new NamespaceAgent(
                    agentName,
                    template.getRemoteFs(),
                    strategy.createLauncher(client(), running, ctx),
                    name,
                    instanceId,
                    template);

            // addNode replaces a node of the same name, so this upgrades the
            // placeholder registered above into one that knows its instance id.
            Jenkins.get().addNode(finalAgent);

            // Do not report the node as provisioned until the agent has actually
            // connected. Completing here would let Jenkins count a node that
            // offers no executors, see the queue still starved, and provision
            // another every few seconds -- one dead agent becomes dozens of
            // paid instances.
            awaitOnline(finalAgent, template, agentName);
            return finalAgent;
        } catch (Exception e) {
            // Roll back both halves so a failure does not leave a paying
            // instance or a phantom node behind.
            final String orphan = instanceId;
            if (orphan != null) {
                try {
                    client().destroyInstance(orphan, "Jenkins agent provisioning failed");
                } catch (RuntimeException | IOException cleanup) {
                    LOGGER.log(Level.WARNING, cleanup, () -> "Failed to clean up instance " + orphan);
                }
            }
            // Remove by name: addNode may have replaced the placeholder object,
            // so the local reference is not necessarily the registered node.
            Node registered = Jenkins.get().getNode(agentName);
            if (registered != null) {
                try {
                    Jenkins.get().removeNode(registered);
                } catch (IOException cleanup) {
                    LOGGER.log(Level.WARNING, cleanup, () -> "Failed to remove node " + agentName);
                }
            }
            throw e;
        }
    }

    /**
     * Blocks until the agent connects, or fails the provisioning attempt.
     *
     * <p>An inbound agent dials in on its own schedule, so "the instance is
     * RUNNING" says nothing about whether Jenkins can use it. If the agent
     * cannot reach the controller -- a Jenkins URL the instance cannot resolve
     * is the usual cause -- this is what stops the provisioner retrying
     * indefinitely.
     */
    private void awaitOnline(NamespaceAgent agent, AgentTemplate template, String agentName) throws Exception {
        Computer computer = agent.toComputer();
        if (computer == null) {
            throw new IOException("Node " + agentName + " has no computer; cannot wait for it to come online.");
        }
        // A no-op for inbound agents, which connect by themselves; for SSH it
        // starts the launch.
        computer.connect(false);

        long timeoutMillis = TimeUnit.SECONDS.toMillis(template.getLaunchTimeoutSeconds());
        long giveUpAt = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < giveUpAt) {
            if (computer.isOnline()) {
                LOGGER.log(Level.INFO, "Agent {0} connected", agentName);
                return;
            }
            Thread.sleep(2000L);
        }
        throw new IOException("Agent " + agentName + " did not connect within "
                + template.getLaunchTimeoutSeconds() + "s. It usually means the instance cannot reach the Jenkins URL ("
                + effectiveJenkinsUrl() + "). Check the agent log in the Namespace dashboard.");
    }

    /**
     * Builds the instance request.
     *
     * <p>Package-private so tests can assert on the request without a live API.
     */
    Compute.CreateInstanceRequest buildRequest(
            AgentTemplate template,
            String agentName,
            LaunchContext ctx,
            AgentLaunchStrategy strategy,
            Compute.InstanceShape shape)
            throws IOException {
        Compute.CreateInstanceRequest.Builder b = Compute.CreateInstanceRequest.newBuilder()
                .setShape(shape)
                .setDocumentedPurpose("Jenkins agent " + agentName + " (profile " + template.getName() + ")")
                // Server-side expiry. This is the backstop that stops a crashed
                // or disconnected controller from leaking instances.
                .setDeadline(NamespaceClient.toTimestamp(
                        Instant.now().plus(Duration.ofMinutes(template.getMaxLifetimeMinutes()))))
                .addLabels(label(LABEL_MANAGED_BY, MANAGED_BY_VALUE))
                .addLabels(label(LABEL_CLOUD, name))
                .addLabels(label(LABEL_AGENT, agentName));

        // Only pin a site when one was explicitly configured. The endpoint
        // prefix ("us"/"eu") is NOT a valid site name, so sending it here makes
        // the API reject the request for having no matching region.
        if (!getInstanceRegion().isBlank()) {
            b.setRegion(getInstanceRegion());
        }

        for (AgentTemplate.CacheVolume v : template.getCacheVolumeList()) {
            b.addVolumes(Compute.VolumeRequest.newBuilder()
                    .setMountPoint(v.mountPoint())
                    .setTag(v.tag())
                    .setSizeMb(v.sizeMb())
                    .setPersistencyKind(Compute.VolumeRequest.PersistencyKind.CACHE)
                    .build());
        }

        strategy.configureInstance(b, ctx);
        return b.build();
    }

    private static Labels.Label label(String name, String value) {
        return Labels.Label.newBuilder().setName(name).setValue(value).build();
    }

    /** The URL the agent will dial back to. */
    public String effectiveJenkinsUrl() throws IOException {
        if (jenkinsUrlOverride != null && !jenkinsUrlOverride.isBlank()) {
            return jenkinsUrlOverride.trim();
        }
        String url = JenkinsLocationConfiguration.get().getUrl();
        if (url == null || url.isBlank()) {
            throw new IOException("Jenkins URL is not configured (Manage Jenkins → System → Jenkins URL), "
                    + "and this cloud has no override. The agent would have nowhere to connect back to.");
        }
        return url;
    }

    @Extension
    @Symbol("namespace")
    public static class DescriptorImpl extends Descriptor<Cloud> {

        @Override
        @NonNull
        public String getDisplayName() {
            return "Namespace";
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item, @QueryParameter String credentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            StandardListBoxModel result = new StandardListBoxModel();
            if (item == null && !Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return result.includeCurrentValue(credentialsId);
            }
            return result.includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            Jenkins.get(),
                            StringCredentials.class,
                            Collections.<DomainRequirement>emptyList(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }

        /**
         * Validates the Project ID, including against the registry itself.
         *
         * <p>Format errors are caught locally; correctness is checked by asking
         * the registry for a repository known to exist, because the gRPC API is
         * tenant-scoped by the token and cannot reveal a wrong prefix.
         */
        @RequirePOST
        public FormValidation doCheckRegistryPrefix(
                @QueryParameter String value, @QueryParameter String credentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            String v = value == null ? "" : value.trim();
            if (v.isEmpty()) {
                return FormValidation.warning("Without this the agent-image list cannot build pullable references. "
                        + "Find it with: nsc registry list");
            }
            if (v.contains("/") || v.contains(":") || v.contains("nscr.io")) {
                return FormValidation.error("Just the workspace id, not a full image reference. For "
                        + "nscr.io/abc012abc012/jenkins-agent:1.0 the id is abc012abc012");
            }
            if (!v.matches("[a-z0-9]+")) {
                return FormValidation.error("Expected lowercase letters and digits only.");
            }

            Secret token = resolveToken(credentialsId);
            if (token == null) {
                return FormValidation.ok("Select a token above to verify this against the registry.");
            }
            try (NamespaceClient client = new NamespaceClient(
                    NamespaceClient.computeEndpointForRegion(NamespaceClient.DEFAULT_REGION), token)) {
                switch (client.verifyWorkspacePrefix(token, v)) {
                    case VERIFIED:
                        return FormValidation.ok("Verified against the registry.");
                    case REJECTED:
                        return FormValidation.error("The registry does not serve nscr.io/" + v
                                + "/... with this token. Check it with: nsc registry list");
                    default:
                        return FormValidation.warning("Could not verify: no images in the registry yet, or it could "
                                + "not be reached. The id may still be correct.");
                }
            } catch (RuntimeException e) {
                return FormValidation.warning("Could not verify the id right now.");
            }
        }

        @POST
        public ListBoxModel doFillRegionItems() {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            ListBoxModel m = new ListBoxModel();
            m.add("US \u2014 us.compute.namespaceapis.com", "us");
            m.add("EU \u2014 eu.compute.namespaceapis.com", "eu");
            return m;
        }

        @POST
        public FormValidation doCheckComputeEndpointOverride(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            if (value.contains("://") || value.contains("/")) {
                return FormValidation.error("Host only, without scheme or path, e.g. us.compute.namespaceapis.com");
            }
            return FormValidation.ok();
        }

        /**
         * Verifies the token can actually reach Namespace, and reports precisely
         * which grants are missing when it cannot.
         *
         * <p>The probe is {@code ListInstances}: it is read-only, creates
         * nothing, and needs only {@code instance:list}. {@code DescribePolicies}
         * would be the obvious call but it requires an <em>admin</em>-scoped
         * token, so a correctly least-privileged Jenkins token would fail it.
         */
        @RequirePOST
        public FormValidation doTestConnection(
                @QueryParameter String region,
                @QueryParameter String computeEndpointOverride,
                @QueryParameter String registryPrefix,
                @QueryParameter String credentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            Secret token = resolveToken(credentialsId);
            if (token == null) {
                return FormValidation.error("Select a Secret text credential holding a Namespace token.");
            }

            String computeHost = computeEndpointOverride != null && !computeEndpointOverride.isBlank()
                    ? computeEndpointOverride.trim()
                    : NamespaceClient.computeEndpointForRegion(
                            region == null || region.isBlank() ? NamespaceClient.DEFAULT_REGION : region);
            try (NamespaceClient client = new NamespaceClient(computeHost, token)) {
                // Probe each action rather than only listing: a token that can
                // list but not destroy provisions happily and then leaks the
                // instance until its deadline.
                NamespaceClient.Permissions p = client.probePermissions();
                String rows = "<br/>&nbsp;&nbsp;" + tick(p.canList()) + " instance: list"
                        + "<br/>&nbsp;&nbsp;" + tick(p.canGet()) + " instance: get"
                        + "<br/>&nbsp;&nbsp;" + tick(p.canWait()) + " instance: wait"
                        + "<br/>&nbsp;&nbsp;" + tick(p.canDestroy()) + " instance: destroy"
                        + "<br/>&nbsp;&nbsp;? instance: create <span style=\"color:#777\">"
                        + "(cannot be checked without creating one)</span>";

                if (!p.allGranted()) {
                    return FormValidation.errorWithMarkup("Connected to <code>"
                            + hudson.Util.escape(computeHost) + "</code>, but the token is missing "
                            + hudson.Util.escape(String.join(", ", p.missing())) + "." + rows
                            + "<br/><br/>Re-mint it with:<pre>"
                            + hudson.Util.escape(RequiredGrants.nscCommand(false)) + "</pre>");
                }
                // The token can be perfectly valid while the Project ID is
                // wrong; without this the mistake only surfaces much later as
                // "failed to resolve image" during a build.
                String prefixNote;
                switch (client.verifyWorkspacePrefix(token, registryPrefix == null ? "" : registryPrefix.trim())) {
                    case VERIFIED:
                        prefixNote = "<br/>\u2713 Project ID verified against the registry.";
                        break;
                    case REJECTED:
                        prefixNote = "<br/>\u2717 <b>Project ID looks wrong</b>: the registry does not serve nscr.io/"
                                + hudson.Util.escape(String.valueOf(registryPrefix))
                                + "/... with this token. Builds would fail with \"failed to resolve image\".";
                        break;
                    default:
                        prefixNote = "<br/><span style=\"color:#777\">Project ID not verified: no images in the "
                                + "registry yet, or it could not be reached.</span>";
                }
                return FormValidation.okWithMarkup("Connected to <code>" + hudson.Util.escape(computeHost)
                        + "</code>. All required instance actions granted." + rows + prefixNote);
            } catch (StatusRuntimeException e) {
                return describeFailure(e, computeHost);
            } catch (RuntimeException e) {
                return FormValidation.error(e, "Could not reach " + computeHost);
            }
        }

        private static String tick(boolean ok) {
            return ok ? "\u2713" : "\u2717";
        }

        private static String grantSummary() {
            return RequiredGrants.mergeByResourceType(RequiredGrants.forConfiguration(false)).stream()
                    .map(RequiredGrants::describe)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("");
        }

        private static FormValidation describeFailure(StatusRuntimeException e, String host) {
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.PERMISSION_DENIED) {
                String missing = PermissionDiagnostics.format(PermissionDiagnostics.missingAccess(e));
                String detail = missing.isBlank()
                        ? "The token is valid but lacks the required grants."
                        : "The token is missing: " + missing + ".";
                return FormValidation.errorWithMarkup(hudson.Util.escape(detail)
                        + "<br/>Mint a token with the needed grants:<pre>"
                        + hudson.Util.escape(RequiredGrants.nscCommand(false))
                        + "</pre>");
            }
            if (code == Status.Code.UNAUTHENTICATED) {
                return FormValidation.error("Namespace rejected the token. Check it has not expired or been revoked "
                        + "(nsc token list).");
            }
            if (code == Status.Code.UNIMPLEMENTED) {
                return FormValidation.error("Host " + host
                        + " does not serve ComputeService (nginx 404). ComputeService is regional \u2014 use "
                        + "us.compute.namespaceapis.com or eu.compute.namespaceapis.com, not a global host.");
            }
            if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
                return FormValidation.error(
                        "Could not reach " + host + " (" + code + "). Check egress from the controller to port 443.");
            }
            return FormValidation.error(code + ": " + e.getStatus().getDescription());
        }

        /** Shown on the configuration page so the operator can mint the right token. */
        public String getNscTokenCommand() {
            return RequiredGrants.nscCommand(true);
        }

        public List<RequiredGrants.Grant> getRequiredGrants() {
            return RequiredGrants.mergeByResourceType(RequiredGrants.forConfiguration(true));
        }
    }
}
