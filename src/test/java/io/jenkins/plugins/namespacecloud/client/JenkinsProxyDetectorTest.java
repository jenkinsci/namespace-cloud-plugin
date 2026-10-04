package io.jenkins.plugins.namespacecloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.ProxyConfiguration;
import io.grpc.HttpConnectProxiedSocketAddress;
import io.grpc.ProxiedSocketAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

/**
 * Whether a gRPC connection is sent through Jenkins' proxy.
 *
 * <p>Without this the plugin cannot reach the Namespace API from a controller
 * that has no direct route out, which is the normal arrangement inside a
 * corporate network.
 */
class JenkinsProxyDetectorTest {

    private static final InetSocketAddress TARGET =
            InetSocketAddress.createUnresolved("us.compute.namespaceapis.com", 443);

    @Test
    void withNoProxyConfiguredTheConnectionGoesDirect() {
        assertNull(JenkinsProxyDetector.proxyFor(TARGET, null), "null means connect directly");
    }

    @Test
    void anEmptyProxyHostIsTreatedAsNoProxy() {
        // Jenkins stores a blank name when the operator clears the field rather
        // than removing the configuration.
        assertNull(JenkinsProxyDetector.proxyFor(TARGET, new ProxyConfiguration("", 3128)));
    }

    @Test
    void aConfiguredProxyIsUsedForTheTarget() {
        // "localhost" rather than a made-up hostname: gRPC rejects an
        // unresolved proxy address, so the detector has to resolve it and the
        // test needs a name that actually resolves.
        ProxiedSocketAddress out = JenkinsProxyDetector.proxyFor(TARGET, new ProxyConfiguration("localhost", 3128));
        HttpConnectProxiedSocketAddress addr = assertInstanceOf(HttpConnectProxiedSocketAddress.class, out);
        assertEquals(TARGET, addr.getTargetAddress());
        assertEquals(3128, ((InetSocketAddress) addr.getProxyAddress()).getPort());
        assertFalse(
                ((InetSocketAddress) addr.getProxyAddress()).isUnresolved(),
                "gRPC throws IllegalStateException on an unresolved proxy address");
        assertNull(addr.getUsername(), "no credentials were configured");
    }

    @Test
    void proxyCredentialsArePassedThroughWhenSet() {
        ProxiedSocketAddress out =
                JenkinsProxyDetector.proxyFor(TARGET, new ProxyConfiguration("localhost", 3128, "alice", "s3cret"));
        HttpConnectProxiedSocketAddress addr = assertInstanceOf(HttpConnectProxiedSocketAddress.class, out);
        assertEquals("alice", addr.getUsername());
        assertEquals("s3cret", addr.getPassword());
    }

    @Test
    void aProxyHostThatDoesNotResolveDoesNotBreakChannelCreation() {
        // Rather than letting gRPC throw out of proxyFor, which would stop the
        // channel being built at all, fall back to a direct connection and warn.
        assertNull(JenkinsProxyDetector.proxyFor(TARGET, new ProxyConfiguration("no-such-proxy.invalid", 3128)));
    }

    @Test
    void aHostOnTheNoProxyListGoesDirect() {
        // The whole point of the no-proxy list: an internal Namespace endpoint
        // must not be sent through the corporate proxy.
        ProxyConfiguration config = new ProxyConfiguration("proxy.corp", 3128, null, null, "*.namespaceapis.com");
        assertNull(JenkinsProxyDetector.proxyFor(TARGET, config));
    }
}
