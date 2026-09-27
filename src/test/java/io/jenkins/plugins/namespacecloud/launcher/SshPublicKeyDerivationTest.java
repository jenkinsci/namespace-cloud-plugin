package io.jenkins.plugins.namespacecloud.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.junit.jupiter.api.Test;

/**
 * The public half injected into the instance is computed from the configured
 * private key, so these two can never disagree.
 *
 * <p>Keys are generated here rather than checked in: a private key in the
 * repository would be flagged by secret scanning, and a generated one proves
 * the same thing.
 */
class SshPublicKeyDerivationTest {

    private static String privateKeyPem(KeyPair pair, String passphrase) throws Exception {
        OpenSSHKeyEncryptionContext encryption = null;
        if (passphrase != null) {
            encryption = new OpenSSHKeyEncryptionContext();
            encryption.setPassword(passphrase);
            // Name, type and mode are concatenated into the cipher spec
            // ("aes256-ctr"); leaving the type unset yields "aesnull-ctr".
            encryption.setCipherName(OpenSSHKeyEncryptionContext.AES);
            encryption.setCipherType("256");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "test key", encryption, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void assertDerivesPublicHalf(String algorithm, int size) throws Exception {
        KeyPair pair = KeyUtils.generateKeyPair(algorithm, size);
        String derived = SshLaunchStrategy.publicKeyOf(privateKeyPem(pair, null), null);
        assertEquals(PublicKeyEntry.toString(pair.getPublic()), derived, algorithm + " public half should round trip");
    }

    @Test
    void anRsaKeyYieldsItsOwnPublicHalf() throws Exception {
        assertDerivesPublicHalf(KeyPairProvider.SSH_RSA, 2048);
    }

    @Test
    void anEd25519KeyYieldsItsOwnPublicHalf() throws Exception {
        // Ed25519 support arrives through the eddsa-api plugin that
        // mina-sshd-api-common depends on; this fails if that drops out.
        assertDerivesPublicHalf(KeyPairProvider.SSH_ED25519, 256);
    }

    @Test
    void aPassphraseProtectedKeyIsUnlockedWithItsPassphrase() throws Exception {
        // The reason the public key used to be configured by hand was the
        // belief that a protected key could not be opened at configuration
        // time. It can: the passphrase is stored on the credential.
        KeyPair pair = KeyUtils.generateKeyPair(KeyPairProvider.SSH_RSA, 2048);
        String pem = privateKeyPem(pair, "correct horse");
        assertEquals(PublicKeyEntry.toString(pair.getPublic()), SshLaunchStrategy.publicKeyOf(pem, "correct horse"));
    }

    @Test
    void aWrongPassphraseIsReportedRatherThanSilentlyIgnored() throws Exception {
        KeyPair pair = KeyUtils.generateKeyPair(KeyPairProvider.SSH_RSA, 2048);
        String pem = privateKeyPem(pair, "correct horse");
        IOException e = assertThrows(IOException.class, () -> SshLaunchStrategy.publicKeyOf(pem, "wrong"));
        // MINA reports this as "Mismatched private key check values", which
        // gives an operator nothing to act on.
        assertTrue(
                e.getMessage().contains("passphrase"),
                "the message should point at the passphrase, was: " + e.getMessage());
    }

    @Test
    void garbageIsRejectedWithAReadableMessage() {
        IOException e = assertThrows(IOException.class, () -> SshLaunchStrategy.publicKeyOf("not a key at all", null));
        assertTrue(e.getMessage().contains("key format"), e.getMessage());
    }
}
