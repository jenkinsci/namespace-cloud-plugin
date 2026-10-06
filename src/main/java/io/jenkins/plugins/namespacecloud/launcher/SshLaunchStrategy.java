package io.jenkins.plugins.namespacecloud.launcher;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.security.ACL;
import hudson.slaves.ComputerLauncher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.namespacecloud.client.NamespaceClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import namespace.cloud.compute.v1beta.Compute;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Connects by SSH from the controller into the instance.
 *
 * <p>Use this when the agent cannot dial out to Jenkins. The controller must be
 * able to reach Namespace's regional ingress, and the token additionally needs
 * the {@code instance:ssh} and {@code ingress:access} grants.
 *
 * <p>Namespace authorises SSH against public keys injected at instance creation.
 * Those are computed from the configured private-key credential rather than
 * configured alongside it: a separately stored public key silently stops
 * matching the moment the credential is rotated, and the failure surfaces only
 * as an agent that cannot be reached.
 */
public class SshLaunchStrategy extends AgentLaunchStrategy {

    /** The image must contain a JRE; the controller copies {@code agent.jar} into it. */
    public static final String DEFAULT_IMAGE = AgentImages.CLEAN_JDK21;

    private String image = DEFAULT_IMAGE;
    private String credentialsId;

    private String javaPath;

    @DataBoundConstructor
    public SshLaunchStrategy(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    public String getImage() {
        return image == null || image.isBlank() ? DEFAULT_IMAGE : image;
    }

    @DataBoundSetter
    public void setImage(String image) {
        this.image = image;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getJavaPath() {
        return javaPath;
    }

    @DataBoundSetter
    public void setJavaPath(String javaPath) {
        this.javaPath = javaPath;
    }

    @Override
    public boolean requiresSshGrants() {
        return true;
    }

    @Override
    public void configureInstance(@NonNull Compute.CreateInstanceRequest.Builder builder, @NonNull LaunchContext ctx)
            throws IOException {
        Map<String, String> env = new LinkedHashMap<>(ctx.template().getEnvironmentMap());

        // The container must stay up for the controller to SSH into it; unlike
        // the inbound strategy nothing inside it starts the agent.
        builder.addContainers(Compute.ContainerRequest.newBuilder()
                .setName("jenkins-agent")
                .setImageRef(getImage())
                .putAllEnvironment(env)
                .addAllEntrypoint(List.of("/bin/sh", "-c"))
                .addArgs("trap : TERM INT; sleep infinity & wait")
                .setWorkloadType(Compute.ContainerRequest.WorkloadType.SERVICE)
                .build());

        Compute.CreateInstanceRequest.ExperimentalFeatures.Builder experimental = builder.getExperimental().toBuilder();
        for (String publicKey : authorizedKeys(credentialsId)) {
            experimental.addAuthorizedSshKeys(publicKey);
        }
        builder.setExperimental(experimental.build());
    }

    /**
     * The OpenSSH public keys matching the configured credential, ready to be
     * injected into the instance.
     *
     * <p>Fails loudly rather than provisioning an instance the controller would
     * then be unable to log in to.
     */
    @NonNull
    static List<String> authorizedKeys(@CheckForNull String credentialsId) throws IOException {
        SSHUserPrivateKey credential = lookup(credentialsId);
        if (credential == null) {
            throw new IOException("No SSH private key credential found with id \"" + credentialsId + "\".");
        }
        List<String> privateKeys = credential.getPrivateKeys();
        if (privateKeys.isEmpty()) {
            throw new IOException("SSH credential \"" + credentialsId + "\" holds no private key.");
        }
        Secret passphrase = credential.getPassphrase();
        String password = passphrase == null ? null : Secret.toString(passphrase);

        List<String> out = new ArrayList<>(privateKeys.size());
        for (String pem : privateKeys) {
            out.add(publicKeyOf(pem, password));
        }
        return out;
    }

    /**
     * Computes the {@code authorized_keys} line for the public half of an
     * OpenSSH private key.
     *
     * <p>Package-private so it can be tested against real generated keys without
     * a credential store.
     */
    @NonNull
    static String publicKeyOf(@NonNull String privateKeyPem, @CheckForNull String passphrase) throws IOException {
        FilePasswordProvider passwords = passphrase == null || passphrase.isEmpty()
                ? FilePasswordProvider.EMPTY
                : FilePasswordProvider.of(passphrase);

        Iterable<KeyPair> pairs;
        try (InputStream in = new ByteArrayInputStream(privateKeyPem.getBytes(StandardCharsets.UTF_8))) {
            pairs = SecurityUtils.loadKeyPairIdentities(
                    null, NamedResource.ofName("jenkins-ssh-credential"), in, passwords);
        } catch (IOException | GeneralSecurityException e) {
            // A wrong passphrase surfaces as a corrupt stream rather than a
            // security exception, and its own message ("Mismatched private key
            // check values") does not suggest the passphrase to an operator.
            throw new IOException(
                    "Could not read the SSH private key. The passphrase on the credential may be wrong or missing, "
                            + "or the key may be in an unsupported format. (" + e.getMessage() + ")",
                    e);
        }

        KeyPair pair = pairs == null
                ? null
                : pairs.iterator().hasNext() ? pairs.iterator().next() : null;
        if (pair == null) {
            throw new IOException("The SSH credential is not in a key format this plugin can read.");
        }
        return PublicKeyEntry.toString(pair.getPublic());
    }

    @CheckForNull
    private static SSHUserPrivateKey lookup(@CheckForNull String credentialsId) {
        if (credentialsId == null || credentialsId.isBlank()) {
            return null;
        }
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        SSHUserPrivateKey.class, Jenkins.get(), ACL.SYSTEM2, Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId));
    }

    /**
     * The credentials id the launcher logs in with.
     *
     * <p>Namespace's ingress routes on the login name, which it hands back from
     * {@code GetSSHConfig}; the username stored on the configured credential is
     * irrelevant to it and gets the key rejected. When Namespace names a user,
     * log in as that user with the configured key. Otherwise keep the configured
     * credential as is.
     */
    @NonNull
    static String loginCredentialsId(@CheckForNull String namespaceUsername, @NonNull String credentialsId) {
        if (namespaceUsername == null || namespaceUsername.isBlank()) {
            return credentialsId;
        }
        return NamespaceSshCredentials.idFor(namespaceUsername, credentialsId);
    }

    @Override
    @NonNull
    public ComputerLauncher createLauncher(
            @NonNull NamespaceClient client, @NonNull Compute.InstanceMetadata instance, @NonNull LaunchContext ctx)
            throws IOException {
        Compute.GetSSHConfigResponse cfg = client.sshConfig(instance.getInstanceId());
        String endpoint = cfg.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IOException("Namespace returned no SSH endpoint for instance " + instance.getInstanceId());
        }

        String host = endpoint;
        int port = 22;
        int colon = endpoint.lastIndexOf(':');
        if (colon > 0 && colon < endpoint.length() - 1) {
            try {
                port = Integer.parseInt(endpoint.substring(colon + 1));
                host = endpoint.substring(0, colon);
            } catch (NumberFormatException e) {
                // Endpoint had no port suffix; fall back to the default.
            }
        }

        SSHLauncher launcher = new SSHLauncher(host, port, loginCredentialsId(cfg.getUsername(), credentialsId));
        // Namespace mints fresh host keys per instance, so pinning them is not
        // possible; the transport is already authenticated by the ingress.
        launcher.setSshHostKeyVerificationStrategy(
                new hudson.plugins.sshslaves.verifiers.NonVerifyingKeyVerificationStrategy());
        if (javaPath != null && !javaPath.isBlank()) {
            launcher.setJavaPath(javaPath);
        }
        return launcher;
    }

    @Extension
    @Symbol("ssh")
    public static class DescriptorImpl extends AgentLaunchStrategyDescriptor {
        @Override
        @NonNull
        public String getDisplayName() {
            return "SSH into the instance";
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item, @QueryParameter String credentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            StandardListBoxModel result = new StandardListBoxModel();
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(credentialsId);
                }
            } else if (!item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                return result.includeCurrentValue(credentialsId);
            }
            return result.includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            Jenkins.get(),
                            SSHUserPrivateKey.class,
                            Collections.<DomainRequirement>emptyList(),
                            NamespaceSshCredentials.NOT_SYNTHETIC)
                    .includeCurrentValue(credentialsId);
        }

        @POST
        public hudson.util.ComboBoxModel doFillImageItems(
                @hudson.RelativePath("../..") @QueryParameter String credentialsId) {
            return AgentImages.suggest(credentialsId);
        }

        /**
         * Reports the fingerprint of the key that will actually be injected.
         *
         * <p>Deriving it here means a key that cannot be read — wrong format, or
         * a passphrase missing from the credential — is reported while the form
         * is open, instead of as an agent that provisions and then never
         * connects.
         */
        @POST
        public FormValidation doCheckCredentialsId(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (value == null || value.isBlank()) {
                return FormValidation.error("Select the SSH private key Jenkins should connect with.");
            }
            try {
                List<String> keys = authorizedKeys(value);
                StringBuilder sb = new StringBuilder("Namespace will authorise ");
                sb.append(keys.size() == 1 ? "this key: " : keys.size() + " keys: ");
                for (int i = 0; i < keys.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(KeyUtils.getFingerPrint(
                            PublicKeyEntry.parsePublicKeyEntry(keys.get(i)).resolvePublicKey(null, null, null)));
                }
                return FormValidation.ok(sb.toString());
            } catch (IOException | GeneralSecurityException e) {
                return FormValidation.error(e.getMessage());
            }
        }
    }
}
