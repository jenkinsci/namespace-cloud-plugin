package io.jenkins.plugins.namespacecloud.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import hudson.model.FreeStyleProject;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.security.ACL;
import hudson.slaves.DumbSlave;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.namespacecloud.AgentTemplate;
import io.jenkins.plugins.namespacecloud.NamespaceAgent;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Namespace's SSH ingress identifies the instance by the login name it returns
 * from {@code GetSSHConfig}. The launcher must log in under that name with the
 * configured key, whatever username the stored credential carries.
 */
class NamespaceSshCredentialsTest {

    private static final String INSTANCE = "iil2a1krrju1m";
    private static final String BASE_ID = "nsc-agent-ssh";

    @Test
    void theNamespaceUsernameIsUsedWhenGiven() {
        String id = SshLaunchStrategy.loginCredentialsId(INSTANCE, BASE_ID);
        assertEquals("namespace-ssh:" + INSTANCE + ":" + BASE_ID, id);
        assertArrayEquals(new String[] {INSTANCE, BASE_ID}, NamespaceSshCredentials.parse(id));
    }

    @Test
    void withoutANamespaceUsernameTheConfiguredCredentialIsUsedAsIs() {
        assertEquals(BASE_ID, SshLaunchStrategy.loginCredentialsId(null, BASE_ID));
        assertEquals(BASE_ID, SshLaunchStrategy.loginCredentialsId("  ", BASE_ID));
    }

    @Test
    void baseIdsMayThemselvesContainColons() {
        String[] parts = NamespaceSshCredentials.parse(NamespaceSshCredentials.idFor(INSTANCE, "folder:key"));
        assertArrayEquals(new String[] {INSTANCE, "folder:key"}, parts);
    }

    @Test
    void foreignOrMalformedIdsAreNotOurs() {
        assertNull(NamespaceSshCredentials.parse(BASE_ID));
        assertNull(NamespaceSshCredentials.parse("namespace-ssh:"));
        assertNull(NamespaceSshCredentials.parse("namespace-ssh:only-a-user:"));
        assertNull(NamespaceSshCredentials.parse("namespace-ssh:bad user:" + BASE_ID));
        assertThrows(IllegalArgumentException.class, () -> NamespaceSshCredentials.idFor("bad user", BASE_ID));
    }

    @Test
    @WithJenkins
    void theLauncherLooksUpTheInstanceUsernameWithTheConfiguredKey(JenkinsRule j) throws Exception {
        String pem = generatedPrivateKey();
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new BasicSSHUserPrivateKey(
                        CredentialsScope.GLOBAL,
                        BASE_ID,
                        "starkware-jenkins",
                        new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(pem),
                        null,
                        "base key"));

        String loginId = SshLaunchStrategy.loginCredentialsId(INSTANCE, BASE_ID);
        NamespaceAgent agent = namespaceAgent(new SSHLauncher("ssh.example", 22, loginId));
        j.jenkins.addNode(agent);

        // Exactly the lookup SSHLauncher performs when it connects.
        StandardUsernameCredentials resolved = SSHLauncher.lookupSystemCredentials(loginId);
        assertNotNull(resolved, "the launcher should find its login");
        assertEquals(INSTANCE, resolved.getUsername());
        SSHUserPrivateKey key = (SSHUserPrivateKey) resolved;
        assertEquals(
                List.of(pem.strip()),
                key.getPrivateKeys().stream().map(String::strip).toList());

        // Never visible to anyone but the system...
        List<SSHUserPrivateKey> asAnonymous = CredentialsProvider.lookupCredentialsInItemGroup(
                SSHUserPrivateKey.class, Jenkins.get(), Jenkins.ANONYMOUS2, Collections.emptyList());
        assertNull(CredentialsMatchers.firstOrNull(asAnonymous, CredentialsMatchers.withId(loginId)));

        // ...nor offered in the credential picker.
        ListBoxModel items = j.jenkins
                .getDescriptorByType(SshLaunchStrategy.DescriptorImpl.class)
                .doFillCredentialsIdItems(null, null);
        assertTrue(items.stream().anyMatch(o -> BASE_ID.equals(o.value)), "the real key is offered");
        assertFalse(
                items.stream().anyMatch(o -> NamespaceSshCredentials.isSynthetic(o.value)),
                "generated logins must not be offered");

        // ...nor to a job, even one running as SYSTEM: a pipeline must not be
        // able to obtain a (possibly SYSTEM-scoped) key through the generated id.
        FreeStyleProject job = j.createFreeStyleProject("top-level");
        List<SSHUserPrivateKey> inJob = CredentialsProvider.lookupCredentialsInItem(
                SSHUserPrivateKey.class, job, ACL.SYSTEM2, Collections.emptyList());
        assertNull(CredentialsMatchers.firstOrNull(inJob, CredentialsMatchers.withId(loginId)));

        // Gone with the node.
        j.jenkins.removeNode(agent);
        assertNull(SSHLauncher.lookupSystemCredentials(loginId));
    }

    @Test
    @WithJenkins
    void aMissingBaseKeyResolvesToNothingRatherThanFailing(JenkinsRule j) throws Exception {
        String loginId = SshLaunchStrategy.loginCredentialsId(INSTANCE, "no-such-key");
        j.jenkins.addNode(namespaceAgent(new SSHLauncher("ssh.example", 22, loginId)));
        assertNull(SSHLauncher.lookupSystemCredentials(loginId));
    }

    @Test
    @WithJenkins
    void aHandConfiguredNodeIsNeverServed(JenkinsRule j) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new BasicSSHUserPrivateKey(
                        CredentialsScope.SYSTEM,
                        BASE_ID,
                        "starkware-jenkins",
                        new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(generatedPrivateKey()),
                        null,
                        "base key"));
        String loginId = SshLaunchStrategy.loginCredentialsId(INSTANCE, BASE_ID);
        j.jenkins.addNode(new DumbSlave("manual", "/tmp/agent", new SSHLauncher("ssh.example", 22, loginId)));
        assertNull(SSHLauncher.lookupSystemCredentials(loginId), "only nodes the plugin created are served");
    }

    private static NamespaceAgent namespaceAgent(SSHLauncher launcher) throws Exception {
        return new NamespaceAgent("nsc-agent", "/tmp/agent", launcher, "cloud", INSTANCE, new AgentTemplate("nsc"));
    }

    private static String generatedPrivateKey() throws Exception {
        KeyPair pair = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "test key", null, out);
        return out.toString(StandardCharsets.UTF_8);
    }
}
