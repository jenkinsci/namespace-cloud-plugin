package io.jenkins.plugins.namespacecloud;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.JNLPLauncher;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * How long an agent outlives its build.
 *
 * <p>The distinction matters for cost: a one-executor instance that lingers for
 * the whole idle timeout after its build is finished is a billable VM doing
 * nothing.
 */
@WithJenkins
class NamespaceAgentRetentionTest {

    private static NamespaceAgent agentWith(int executors) throws Exception {
        AgentTemplate t = new AgentTemplate("builder");
        t.setNumExecutors(executors);
        t.setIdleMinutes(7);
        return new NamespaceAgent("builder-abc123", t.getRemoteFs(), new JNLPLauncher(), "ns", "i-1", t);
    }

    @Test
    void aSingleExecutorProfileIsDestroyedAsSoonAsItsBuildFinishes(JenkinsRule j) throws Exception {
        assertInstanceOf(
                OnceRetentionStrategy.class,
                agentWith(1).getRetentionStrategy(),
                "a one-executor instance can only serve one build, so it should not wait out the idle timeout");
    }

    @Test
    void aMultiExecutorProfileWaitsOutTheIdleTimeout(JenkinsRule j) throws Exception {
        // Terminating after the first build would kill the builds still running
        // on the other executors.
        assertInstanceOf(CloudRetentionStrategy.class, agentWith(4).getRetentionStrategy());
    }
}
