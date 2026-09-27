package io.jenkins.plugins.namespacecloud.launcher;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.slaves.ComputerLauncher;
import io.jenkins.plugins.namespacecloud.client.NamespaceClient;
import java.io.IOException;
import namespace.cloud.compute.v1beta.Compute;

/**
 * How a provisioned Namespace instance is connected back to Jenkins.
 *
 * <p>Two directions are possible and they need opposite network paths, which is
 * why this is a strategy rather than a flag:
 *
 * <ul>
 *   <li>{@link InboundLaunchStrategy} — the instance dials out to the controller
 *       over HTTP(S)/WebSocket. Needs no inbound firewall rule.
 *   <li>{@link SshLaunchStrategy} — the controller dials in to the instance.
 *       Needs egress from Jenkins to Namespace's ingress.
 * </ul>
 */
public abstract class AgentLaunchStrategy extends AbstractDescribableImpl<AgentLaunchStrategy>
        implements ExtensionPoint {

    /**
     * Contributes to the instance request before it is sent — typically the
     * container that will host the agent, plus any credentials it needs.
     *
     * @throws IOException if the request cannot be completed, for instance
     *     because a configured credential is missing or unreadable. Failing
     *     here is preferable to provisioning an instance the controller would
     *     then be unable to connect to.
     */
    public abstract void configureInstance(
            @NonNull Compute.CreateInstanceRequest.Builder builder, @NonNull LaunchContext ctx) throws IOException;

    /**
     * Builds the Jenkins-side launcher. Called once the instance is RUNNING, so
     * implementations may query the instance for connection details.
     */
    @NonNull
    public abstract ComputerLauncher createLauncher(
            @NonNull NamespaceClient client, @NonNull Compute.InstanceMetadata instance, @NonNull LaunchContext ctx)
            throws IOException, InterruptedException;

    /**
     * Whether the Jenkins node must be registered before the instance is created.
     * True for inbound: the agent connects on its own and the controller rejects
     * a connection from a node it does not know about yet.
     */
    public boolean requiresNodeRegisteredFirst() {
        return false;
    }

    /** Whether this strategy needs the {@code instance:ssh} and {@code ingress:access} grants. */
    public boolean requiresSshGrants() {
        return false;
    }

    @Override
    public Descriptor<AgentLaunchStrategy> getDescriptor() {
        return (Descriptor<AgentLaunchStrategy>) super.getDescriptor();
    }

    /** Base descriptor so both strategies show up in the same dropdown. */
    public abstract static class AgentLaunchStrategyDescriptor extends Descriptor<AgentLaunchStrategy> {}
}
