package io.jenkins.plugins.namespacecloud.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.util.ComboBoxModel;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class AgentImagesTest {

    @Test
    void withoutACredentialTheStockImagesAreStillOffered(JenkinsRule j) {
        // The form must stay usable before a token is selected, and must never
        // fail just because the registry cannot be queried.
        ComboBoxModel m = AgentImages.suggest(null);
        assertTrue(m.contains(AgentImages.CLEAN_JDK21), "the clean JDK21 image must always be offered");
        assertEquals(1, m.size(), "no registry access means stock images only");
    }

    @Test
    void anUnknownCredentialDegradesRatherThanThrowing(JenkinsRule j) {
        ComboBoxModel m = AgentImages.suggest("no-such-credential-id");
        assertTrue(m.contains(AgentImages.CLEAN_JDK21));
        assertFalse(m.isEmpty());
    }

    @Test
    void noStockImageRunsAJdkOlderThanTheBaselineSupports(JenkinsRule j) {
        // The 2.555.x baseline cannot talk to agents on Java 17, so suggesting
        // such an image would provision an instance that never connects.
        for (String ref : AgentImages.suggest(null)) {
            assertFalse(ref.contains("jdk17"), "must not offer a jdk17 agent image: " + ref);
        }
    }

    @Test
    void theDefaultAgentImageIsAStockCleanImage(JenkinsRule j) {
        // "Clean instance" is just the stock image; the two must not drift.
        assertEquals(AgentImages.CLEAN_JDK21, new InboundLaunchStrategy().getImage());
        assertEquals(AgentImages.CLEAN_JDK21, InboundLaunchStrategy.DEFAULT_IMAGE);
        assertEquals(AgentImages.CLEAN_JDK21, SshLaunchStrategy.DEFAULT_IMAGE);
    }
}
