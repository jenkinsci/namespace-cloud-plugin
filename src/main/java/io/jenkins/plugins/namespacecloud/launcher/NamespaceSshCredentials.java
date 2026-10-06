package io.jenkins.plugins.namespacecloud.launcher;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.IdCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Node;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.security.ACL;
import hudson.util.Secret;
import io.jenkins.plugins.namespacecloud.NamespaceAgent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.springframework.security.core.Authentication;

/**
 * Serves the SSH login for a Namespace instance under the username Namespace
 * asks for.
 *
 * <p>Namespace's SSH ingress identifies the target instance by the login name
 * (it is the instance id, returned by {@code GetSSHConfig}), so no single
 * username stored on a Jenkins credential can match every agent. The key, on
 * the other hand, is the configured credential, since its public half is what
 * was injected into the instance.
 *
 * <p>{@link SSHLauncher} only accepts a credentials id, so the launcher is
 * given a synthetic id of the form {@code namespace-ssh:<username>:<baseId>} and
 * this provider resolves it to a copy of the base credential with that
 * username. Nothing is stored: the credential is rebuilt from the node's own
 * launcher on every lookup, so it survives a controller restart and disappears
 * with the node. Only system lookups ({@link ACL#SYSTEM2}) at the root see it,
 * which is exactly how {@link SSHLauncher} looks credentials up, and it never
 * shows up in a credential picker.
 */
@Extension
public class NamespaceSshCredentials extends CredentialsProvider {

    static final String PREFIX = "namespace-ssh:";

    /** Namespace usernames are instance ids; anything else is not ours to serve. */
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]+");

    /** The base lookup goes back through every provider, this one included. */
    private static final ThreadLocal<Boolean> RESOLVING = ThreadLocal.withInitial(() -> false);

    /** Excludes the synthetic credentials from listings shown to users. */
    public static final CredentialsMatcher NOT_SYNTHETIC = CredentialsMatchers.not(new SyntheticMatcher());

    /**
     * The credentials id to hand {@link SSHLauncher} for logging in as
     * {@code username} with the key from {@code baseCredentialsId}.
     */
    @NonNull
    static String idFor(@NonNull String username, @NonNull String baseCredentialsId) {
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("Unexpected Namespace SSH username: " + username);
        }
        return PREFIX + username + ":" + baseCredentialsId;
    }

    static boolean isSynthetic(@CheckForNull String id) {
        return id != null && id.startsWith(PREFIX);
    }

    /** {@code [username, baseCredentialsId]}, or {@code null} if the id is not one of ours. */
    @CheckForNull
    static String[] parse(@CheckForNull String id) {
        if (!isSynthetic(id)) {
            return null;
        }
        String rest = id.substring(PREFIX.length());
        int sep = rest.indexOf(':');
        if (sep <= 0 || sep == rest.length() - 1) {
            return null;
        }
        String username = rest.substring(0, sep);
        if (!USERNAME.matcher(username).matches()) {
            return null;
        }
        return new String[] {username, rest.substring(sep + 1)};
    }

    @Override
    @NonNull
    public <C extends Credentials> List<C> getCredentialsInItemGroup(
            @NonNull Class<C> type,
            @CheckForNull ItemGroup itemGroup,
            @CheckForNull Authentication authentication,
            @NonNull List<DomainRequirement> domainRequirements) {
        if (!(itemGroup instanceof Jenkins)
                || !ACL.SYSTEM2.equals(authentication)
                || !type.isAssignableFrom(BasicSSHUserPrivateKey.class)
                || RESOLVING.get()) {
            return Collections.emptyList();
        }
        List<C> out = new ArrayList<>();
        for (Node node : Jenkins.get().getNodes()) {
            C credential = credentialFor(type, node);
            if (credential != null) {
                out.add(credential);
            }
        }
        return out;
    }

    /**
     * Never served in an item's context.
     *
     * <p>The default implementation falls back to the item's parent group, which
     * for a top-level job is the root; and builds commonly run as SYSTEM. Without
     * this override a pipeline could ask for {@code namespace-ssh:<x>:<id>} and
     * receive the key of a SYSTEM-scoped credential it is not allowed to use.
     * The launcher looks up at the root, never through an item.
     */
    @Override
    @NonNull
    public <C extends Credentials> List<C> getCredentialsInItem(
            @NonNull Class<C> type,
            @NonNull Item item,
            @CheckForNull Authentication authentication,
            @NonNull List<DomainRequirement> domainRequirements) {
        return Collections.emptyList();
    }

    @CheckForNull
    private static <C extends Credentials> C credentialFor(@NonNull Class<C> type, @NonNull Node node) {
        // Only nodes this plugin created; a hand-configured node never gets here.
        if (!(node instanceof NamespaceAgent agent) || !(agent.getLauncher() instanceof SSHLauncher launcher)) {
            return null;
        }
        String id = launcher.getCredentialsId();
        String[] parts = parse(id);
        if (parts == null) {
            return null;
        }
        SSHUserPrivateKey base = lookupBase(parts[1]);
        if (base == null) {
            return null;
        }
        Secret passphrase = base.getPassphrase();
        return type.cast(new BasicSSHUserPrivateKey(
                CredentialsScope.SYSTEM,
                id,
                parts[0],
                new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(base.getPrivateKeys()),
                passphrase == null ? null : Secret.toString(passphrase),
                "Namespace SSH login for " + node.getNodeName() + " (key: " + parts[1] + ")"));
    }

    @CheckForNull
    private static SSHUserPrivateKey lookupBase(@NonNull String baseCredentialsId) {
        RESOLVING.set(true);
        try {
            return CredentialsMatchers.firstOrNull(
                    CredentialsProvider.lookupCredentialsInItemGroup(
                            SSHUserPrivateKey.class, Jenkins.get(), ACL.SYSTEM2, Collections.emptyList()),
                    CredentialsMatchers.withId(baseCredentialsId));
        } finally {
            RESOLVING.remove();
        }
    }

    @Override
    @NonNull
    public String getDisplayName() {
        return "Namespace SSH logins (generated per agent)";
    }

    private static final class SyntheticMatcher implements CredentialsMatcher {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean matches(@NonNull Credentials item) {
            return item instanceof IdCredentials ic && isSynthetic(ic.getId());
        }
    }
}
