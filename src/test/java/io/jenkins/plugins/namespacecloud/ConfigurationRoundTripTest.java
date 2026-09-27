package io.jenkins.plugins.namespacecloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.namespacecloud.launcher.InboundLaunchStrategy;
import io.jenkins.plugins.namespacecloud.launcher.SshLaunchStrategy;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Exercises the configuration forms by submitting them. This is the only thing
 * that actually parses the Jelly files, so a typo in a field name shows up here
 * rather than as an empty form in production.
 */
@WithJenkins
class ConfigurationRoundTripTest {

    @Test
    void inboundProfileSurvivesRoundTrip(JenkinsRule j) throws Exception {
        AgentTemplate template = new AgentTemplate("linux-8x16");
        template.setLabels("ns-linux ns-big");
        template.setVcpu(8);
        template.setMemoryGb(16);
        template.setArch("amd64");
        template.setIdleMinutes(7);
        template.setMaxLifetimeMinutes(90);
        template.setEnvironment("FOO=bar\n# comment\nBAZ=qux");
        template.setCacheVolumes("/home/jenkins/.m2:maven:20");

        InboundLaunchStrategy inbound = new InboundLaunchStrategy();
        inbound.setImage("jenkins/inbound-agent:latest-jdk21");
        inbound.setExposeDockerSocket(true);
        template.setLaunchStrategy(inbound);

        NamespaceCloud cloud = new NamespaceCloud("ns");
        cloud.setRegion("eu");
        cloud.setDisplayName("Namespace (EU)");
        cloud.setTemplates(List.of(template));
        Jenkins.get().clouds.add(cloud);

        j.configRoundtrip();

        // The name is submitted from a hidden field, so a round trip is the only
        // thing that catches it going missing and the cloud losing its identity.
        NamespaceCloud after = (NamespaceCloud) Jenkins.get().clouds.getByName("ns");
        assertNotNull(after, "cloud should survive a config round trip");
        assertEquals("Namespace (EU)", after.getDisplayName());
        assertEquals("eu", after.getRegion());
        assertEquals("eu.compute.namespaceapis.com", after.getComputeEndpoint());
        assertEquals(1, after.getTemplates().size());

        AgentTemplate t = after.getTemplates().get(0);
        assertEquals("linux-8x16", t.getName());
        assertEquals("ns-linux ns-big", t.getLabels());
        assertEquals(8, t.getVcpu());
        assertEquals(16, t.getMemoryGb());
        assertEquals("amd64", t.getArch());
        assertEquals(7, t.getIdleMinutes());
        assertEquals(90, t.getMaxLifetimeMinutes());
        assertEquals("bar", t.getEnvironmentMap().get("FOO"));
        assertEquals("qux", t.getEnvironmentMap().get("BAZ"));
        assertEquals(2, t.getEnvironmentMap().size(), "the comment line must not become a variable");

        InboundLaunchStrategy strategy = assertInstanceOf(InboundLaunchStrategy.class, t.getLaunchStrategy());
        assertEquals("jenkins/inbound-agent:latest-jdk21", strategy.getImage());
        assertTrue(strategy.isExposeDockerSocket());

        assertEquals(1, t.getCacheVolumeList().size());
        assertEquals("/home/jenkins/.m2", t.getCacheVolumeList().get(0).mountPoint());
        assertEquals(20L, t.getCacheVolumeList().get(0).sizeGb());
        assertEquals(20480L, t.getCacheVolumeList().get(0).sizeMb());
    }

    @Test
    void sshProfileSurvivesRoundTrip(JenkinsRule j) throws Exception {
        AgentTemplate template = new AgentTemplate("ssh-profile");
        template.setLabels("ns-ssh");
        template.setLaunchStrategy(new SshLaunchStrategy("creds-id"));

        NamespaceCloud cloud = new NamespaceCloud("ns-ssh-cloud");
        cloud.setTemplates(List.of(template));
        Jenkins.get().clouds.add(cloud);

        j.configRoundtrip();

        NamespaceCloud after = (NamespaceCloud) Jenkins.get().clouds.getByName("ns-ssh-cloud");
        assertNotNull(after);
        SshLaunchStrategy strategy = assertInstanceOf(
                SshLaunchStrategy.class, after.getTemplates().get(0).getLaunchStrategy());
        assertEquals("creds-id", strategy.getCredentialsId());
        assertTrue(strategy.requiresSshGrants(), "SSH launching needs the extra instance:ssh grant");
    }

    @Test
    void aCloudWithoutADisplayNameFallsBackToItsName(JenkinsRule j) {
        // Existing configurations have no displayName stored; the UI must not
        // show a blank cloud for them.
        assertEquals("ns-unnamed", new NamespaceCloud("ns-unnamed").getDisplayName());
    }
}
