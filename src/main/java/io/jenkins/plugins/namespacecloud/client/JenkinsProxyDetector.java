package io.jenkins.plugins.namespacecloud.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ProxyConfiguration;
import hudson.util.Secret;
import io.grpc.HttpConnectProxiedSocketAddress;
import io.grpc.ProxiedSocketAddress;
import io.grpc.ProxyDetector;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import jenkins.model.Jenkins;

/**
 * Routes gRPC connections through the proxy configured in Jenkins.
 *
 * <p>gRPC does not consult Jenkins' proxy settings on its own, so without this a
 * controller that can only reach the internet through a proxy cannot talk to the
 * Namespace API at all. The HTTP calls to the container registry go through
 * {@link ProxyConfiguration#open} for the same reason.
 *
 * <p>Returning {@code null} means "connect directly", which is also what happens
 * when the target matches Jenkins' no-proxy list.
 */
final class JenkinsProxyDetector implements ProxyDetector {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(JenkinsProxyDetector.class.getName());

    @Override
    @CheckForNull
    public ProxiedSocketAddress proxyFor(SocketAddress target) {
        // Null outside a running Jenkins, which is how the client is exercised
        // in unit tests.
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        return jenkins == null ? null : proxyFor(target, jenkins.proxy);
    }

    /**
     * The decision itself, separated from the Jenkins singleton so it can be
     * tested against a configuration object directly.
     */
    @CheckForNull
    static ProxiedSocketAddress proxyFor(@NonNull SocketAddress target, @CheckForNull ProxyConfiguration config) {
        if (config == null || !(target instanceof InetSocketAddress address)) {
            return null;
        }
        if (config.getName() == null || config.getName().isBlank()) {
            return null;
        }

        // createProxy applies the "No Proxy Host" patterns, answering
        // Proxy.NO_PROXY (type DIRECT) for a host that should bypass it.
        Proxy proxy = config.createProxy(address.getHostString());
        if (proxy == null || proxy.type() != Proxy.Type.HTTP || proxy.address() == null) {
            return null;
        }

        // Jenkins hands back an unresolved address, which gRPC rejects outright,
        // so resolve it here. A proxy host that does not resolve is a
        // misconfiguration worth saying out loud: connecting directly instead
        // would otherwise fail later with an unrelated-looking timeout.
        InetSocketAddress proxyAddress = resolve(proxy.address());
        if (proxyAddress == null) {
            return null;
        }

        HttpConnectProxiedSocketAddress.Builder builder = HttpConnectProxiedSocketAddress.newBuilder()
                .setTargetAddress(address)
                .setProxyAddress(proxyAddress);

        String user = config.getUserName();
        if (user != null && !user.isBlank()) {
            Secret password = config.getSecretPassword();
            builder.setUsername(user).setPassword(password == null ? "" : Secret.toString(password));
        }
        return builder.build();
    }

    /**
     * Resolves the proxy's own address, which gRPC requires.
     *
     * @return null if the proxy host cannot be resolved, having logged why.
     */
    @CheckForNull
    private static InetSocketAddress resolve(@CheckForNull java.net.SocketAddress address) {
        if (!(address instanceof InetSocketAddress proxyAddress)) {
            return null;
        }
        if (!proxyAddress.isUnresolved()) {
            return proxyAddress;
        }
        InetSocketAddress resolved = new InetSocketAddress(proxyAddress.getHostString(), proxyAddress.getPort());
        if (resolved.isUnresolved()) {
            LOGGER.log(
                    java.util.logging.Level.WARNING,
                    "Jenkins is configured to use the proxy {0}, but that host does not resolve. "
                            + "Connections to the Namespace API will be attempted directly and will most likely fail.",
                    proxyAddress.getHostString());
            return null;
        }
        return resolved;
    }
}
