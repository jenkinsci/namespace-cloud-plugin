package io.jenkins.plugins.namespacecloud.launcher;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.util.ComboBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.namespacecloud.NamespaceCloud;
import io.jenkins.plugins.namespacecloud.client.NamespaceClient;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Builds the agent-image suggestion list shown on a profile.
 *
 * <p>The field stays free text: these are suggestions, and an image from any
 * public registry remains valid. The list exists so an operator who has pushed
 * a prebaked CI image to the workspace registry can pick it instead of
 * retyping a long reference.
 */
public final class AgentImages {

    private static final Logger LOGGER = Logger.getLogger(AgentImages.class.getName());

    /**
     * Stock agent image. Picking this is the "clean instance" case: no
     * preinstalled tooling beyond a JDK and the Jenkins agent itself.
     *
     * <p>JDK 21 only. Jenkins 2.555.x, this plugin's baseline, no longer
     * supports agents running Java 17, so a jdk17 image would provision an
     * instance that then fails to connect.
     */
    public static final String CLEAN_JDK21 = "jenkins/inbound-agent:latest-jdk21";

    private AgentImages() {}

    /**
     * Stock images first, then whatever the workspace registry holds.
     *
     * <p>Never throws: a registry that cannot be reached, or a token without
     * registry access, degrades to the stock list rather than an empty or
     * broken form field.
     */
    public static ComboBoxModel suggest(@CheckForNull String credentialsId) {
        Set<String> out = new LinkedHashSet<>();
        out.add(CLEAN_JDK21);

        // Listing the workspace registry reveals what images an organisation
        // builds, and it spends a stored credential to do so. Neither should be
        // available to a user who cannot administer Jenkins; they still get the
        // stock images, so the form stays usable.
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return toModel(out);
        }

        NamespaceCloud cloud = owningCloud(credentialsId);
        Secret token = cloud == null
                ? NamespaceCloud.resolveToken(credentialsId)
                : NamespaceCloud.resolveToken(cloud.getCredentialsId());
        if (token == null) {
            return toModel(out);
        }
        String prefix = cloud == null ? "" : cloud.getRegistryPrefix();

        try (NamespaceClient client =
                new NamespaceClient(NamespaceClient.computeEndpointForRegion(NamespaceClient.DEFAULT_REGION), token)) {
            out.addAll(client.listImageRefs(100, prefix));
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "Could not list workspace registry images; offering stock images only");
        }
        return toModel(out);
    }

    /**
     * Resolves a usable token, preferring the one passed from the form.
     *
     * <p>The form value arrives via a relative path from a doubly-nested
     * describable (cloud -> template -> launch strategy), which is brittle: if
     * that path is wrong, or the field has not been filled in yet, the
     * parameter is simply empty and the image list would silently shrink to the
     * stock entries. Falling back to any configured Namespace cloud's
     * credential makes the list work regardless.
     */
    @CheckForNull
    private static Secret resolveAnyToken(@CheckForNull String credentialsId) {
        Secret token = NamespaceCloud.resolveToken(credentialsId);
        if (token != null) {
            return token;
        }
        NamespaceCloud c = owningCloud(credentialsId);
        return c == null ? null : NamespaceCloud.resolveToken(c.getCredentialsId());
    }

    /**
     * The cloud whose settings apply to this form, preferring one that uses the
     * credential passed in and otherwise falling back to the first configured
     * Namespace cloud. The fallback matters because the credential arrives via
     * a relative form path from a doubly-nested describable, which is easy to
     * get wrong and fails silently.
     */
    @CheckForNull
    private static NamespaceCloud owningCloud(@CheckForNull String credentialsId) {
        NamespaceCloud first = null;
        for (hudson.slaves.Cloud c : jenkins.model.Jenkins.get().clouds) {
            if (c instanceof NamespaceCloud nc) {
                if (first == null) {
                    first = nc;
                }
                if (credentialsId != null && credentialsId.equals(nc.getCredentialsId())) {
                    return nc;
                }
            }
        }
        return first;
    }

    /**
     * Explains why the list is short, rather than leaving an operator guessing
     * whether the registry is empty or the lookup failed.
     */
    public static hudson.util.FormValidation describeAvailability(@CheckForNull String credentialsId) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return hudson.util.FormValidation.ok();
        }
        Secret token = resolveAnyToken(credentialsId);
        if (token == null) {
            return hudson.util.FormValidation.warning("No usable Namespace token found, so only the stock images are "
                    + "listed. Select a valid credential on the cloud above, then reopen this page.");
        }
        try (NamespaceClient client =
                new NamespaceClient(NamespaceClient.computeEndpointForRegion(NamespaceClient.DEFAULT_REGION), token)) {
            NamespaceCloud cloud = owningCloud(credentialsId);
            String prefix = cloud == null ? "" : cloud.getRegistryPrefix();
            int n = client.listImageRefs(100, prefix).size();
            if (n == 0) {
                return hudson.util.FormValidation.ok("No images in the workspace registry yet; stock images only.");
            }
            if (prefix.isBlank()) {
                return hudson.util.FormValidation.warning(n + " registry image(s) found, but the cloud has no "
                        + "\"Registry workspace prefix\" set, so the suggested references are missing the workspace "
                        + "segment and Namespace will answer \"failed to resolve image\".");
            }
            return hudson.util.FormValidation.ok(n + " image(s) available from the workspace registry.");
        } catch (RuntimeException e) {
            return hudson.util.FormValidation.warning(
                    "Could not read the workspace registry (" + e.getMessage() + "). Stock images only.");
        }
    }

    private static ComboBoxModel toModel(Set<String> values) {
        ComboBoxModel m = new ComboBoxModel();
        m.addAll(values);
        return m;
    }
}
