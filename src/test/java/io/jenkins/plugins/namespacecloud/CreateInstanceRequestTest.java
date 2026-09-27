package io.jenkins.plugins.namespacecloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.namespacecloud.client.NamespaceClient;
import io.jenkins.plugins.namespacecloud.launcher.InboundLaunchStrategy;
import io.jenkins.plugins.namespacecloud.launcher.LaunchContext;
import java.time.Instant;
import java.util.Map;
import namespace.cloud.compute.v1beta.Compute;
import namespace.stdlib.Labels;
import org.junit.jupiter.api.Test;

/**
 * Verifies the instance request the plugin sends to Namespace.
 *
 * <p>No JenkinsRule: building the request touches no Jenkins singletons, so
 * these stay fast unit tests.
 */
class CreateInstanceRequestTest {

    private static AgentTemplate template() {
        AgentTemplate t = new AgentTemplate("builder");
        t.setLabels("ns-builder");
        t.setVcpu(8);
        t.setMemoryGb(16);
        t.setArch("arm64");
        t.setMaxLifetimeMinutes(45);
        t.setEnvironment("BUILD_ENV=ci");
        t.setCacheVolumes("/root/.cache:shared-cache:4");
        return t;
    }

    private static LaunchContext ctx(AgentTemplate t) {
        return new LaunchContext("builder-abc123", "s3cr3t", "https://jenkins.example.com/", t.getRemoteFs(), t);
    }

    @Test
    void shapeAndDeadlineAreSet() throws Exception {
        AgentTemplate t = template();
        NamespaceCloud cloud = new NamespaceCloud("ns");
        Compute.CreateInstanceRequest req =
                cloud.buildRequest(t, "builder-abc123", ctx(t), t.getLaunchStrategy(), t.toInstanceShape());

        assertEquals(8, req.getShape().getVirtualCpu());
        // Configured as 16 GB; the API is told 16384 MB.
        assertEquals(16384, req.getShape().getMemoryMegabytes());
        assertEquals("arm64", req.getShape().getMachineArch());
        assertEquals("linux", req.getShape().getOs());

        // The deadline is the backstop that stops a crashed controller leaking
        // instances, so it must always be set and in the future.
        assertTrue(req.hasDeadline(), "every instance must carry a server-side deadline");
        Instant deadline = NamespaceClient.toInstant(req.getDeadline());
        assertTrue(deadline.isAfter(Instant.now().plusSeconds(40 * 60)), "deadline ~45min out, was " + deadline);
        assertTrue(deadline.isBefore(Instant.now().plusSeconds(50 * 60)), "deadline ~45min out, was " + deadline);
    }

    @Test
    void instancesAreLabelledForOrphanReaping() throws Exception {
        AgentTemplate t = template();
        NamespaceCloud cloud = new NamespaceCloud("my-cloud");
        Compute.CreateInstanceRequest req =
                cloud.buildRequest(t, "builder-abc123", ctx(t), t.getLaunchStrategy(), t.toInstanceShape());

        Map<String, String> labels = new java.util.HashMap<>();
        for (Labels.Label l : req.getLabelsList()) {
            labels.put(l.getName(), l.getValue());
        }
        assertEquals(NamespaceCloud.MANAGED_BY_VALUE, labels.get(NamespaceCloud.LABEL_MANAGED_BY));
        assertEquals("my-cloud", labels.get(NamespaceCloud.LABEL_CLOUD));
        assertEquals("builder-abc123", labels.get(NamespaceCloud.LABEL_AGENT));
    }

    @Test
    void inboundStrategyInjectsAgentCredentials() throws Exception {
        AgentTemplate t = template();
        InboundLaunchStrategy inbound = new InboundLaunchStrategy();
        inbound.setExposeDockerSocket(true);
        t.setLaunchStrategy(inbound);

        NamespaceCloud cloud = new NamespaceCloud("ns");
        Compute.CreateInstanceRequest req =
                cloud.buildRequest(t, "builder-abc123", ctx(t), inbound, t.toInstanceShape());

        assertEquals(1, req.getContainersCount());
        Compute.ContainerRequest c = req.getContainers(0);
        assertEquals("jenkins/inbound-agent:latest-jdk21", c.getImageRef());
        assertEquals(Compute.ContainerRequest.WorkloadType.SERVICE, c.getWorkloadType());
        assertEquals("https://jenkins.example.com/", c.getEnvironmentMap().get("JENKINS_URL"));
        assertEquals("builder-abc123", c.getEnvironmentMap().get("JENKINS_AGENT_NAME"));
        assertEquals("s3cr3t", c.getEnvironmentMap().get("JENKINS_SECRET"));
        assertEquals("true", c.getEnvironmentMap().get("JENKINS_WEB_SOCKET"));
        // Template environment must reach the build, not just the agent wiring.
        assertEquals("ci", c.getEnvironmentMap().get("BUILD_ENV"));
        assertEquals("/var/run/docker.sock", c.getDockerSockPath());
    }

    @Test
    void cacheVolumesArePassedThrough() throws Exception {
        AgentTemplate t = template();
        NamespaceCloud cloud = new NamespaceCloud("ns");
        Compute.CreateInstanceRequest req =
                cloud.buildRequest(t, "builder-abc123", ctx(t), t.getLaunchStrategy(), t.toInstanceShape());

        assertEquals(1, req.getVolumesCount());
        Compute.VolumeRequest v = req.getVolumes(0);
        assertEquals("/root/.cache", v.getMountPoint());
        assertEquals("shared-cache", v.getTag());
        assertEquals(4096L, v.getSizeMb(), "4 GB must reach the API as 4096 MB");
        assertEquals(Compute.VolumeRequest.PersistencyKind.CACHE, v.getPersistencyKind());
    }

    @Test
    void theEndpointPrefixIsNeverSentAsTheInstanceRegion() throws Exception {
        // Regression: "us"/"eu" select the API host but are not site names.
        // Sending one as the region makes Namespace reject the request with
        // "no available region to start a linux/amd64 instance".
        AgentTemplate t = template();
        NamespaceCloud cloud = new NamespaceCloud("ns");
        cloud.setRegion("eu");
        Compute.CreateInstanceRequest req =
                cloud.buildRequest(t, "builder-abc123", ctx(t), t.getLaunchStrategy(), t.toInstanceShape());
        assertEquals("eu.compute.namespaceapis.com", cloud.getComputeEndpoint(), "endpoint still follows region");
        assertEquals("", req.getRegion(), "no site pinned, so Namespace picks one with capacity");
    }

    @Test
    void everyInstanceLabelNameIsValidForNamespace() throws Exception {
        // Namespace documents label names as matching this regex, max 63 bytes.
        // A Kubernetes-style "jenkins.io/agent" is rejected for the slash, and
        // CreateInstance then fails with a bare INVALID_ARGUMENT.
        java.util.regex.Pattern valid = java.util.regex.Pattern.compile("^[a-z]([a-z0-9-.]*[a-z0-9])?$");

        AgentTemplate t = template();
        NamespaceCloud cloud = new NamespaceCloud("Namespace_Runners");
        Compute.CreateInstanceRequest req =
                cloud.buildRequest(t, "builder-abc123", ctx(t), t.getLaunchStrategy(), t.toInstanceShape());

        assertTrue(req.getLabelsCount() > 0, "instances must be labelled so orphans can be reaped");
        for (Labels.Label l : req.getLabelsList()) {
            assertTrue(
                    valid.matcher(l.getName()).matches(),
                    "label name \"" + l.getName() + "\" does not match Namespace's documented pattern");
            assertTrue(l.getName().length() <= 63, "label name too long: " + l.getName());
        }
    }

    @Test
    void anExplicitInstanceRegionIsHonoured() throws Exception {
        AgentTemplate t = template();
        NamespaceCloud cloud = new NamespaceCloud("ns");
        cloud.setInstanceRegion("ord");
        Compute.CreateInstanceRequest req =
                cloud.buildRequest(t, "builder-abc123", ctx(t), t.getLaunchStrategy(), t.toInstanceShape());
        assertEquals("ord", req.getRegion());
    }

    @Test
    void computeEndpointIsRegionalAndOverridable() {
        NamespaceCloud cloud = new NamespaceCloud("ns");
        assertEquals("us.compute.namespaceapis.com", cloud.getComputeEndpoint(), "default region is us");
        cloud.setComputeEndpointOverride("my.compute.internal");
        assertEquals("my.compute.internal", cloud.getComputeEndpoint());
    }
}
