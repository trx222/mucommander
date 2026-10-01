package com.mucommander.commons.file.impl.sftp;

import com.mucommander.commons.file.Credentials;
import com.mucommander.commons.file.FileURL;
import com.mucommander.commons.file.connection.ConnectionHandler;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive;
import net.schmizz.sshj.userauth.method.AuthMethod;
import net.schmizz.sshj.userauth.method.AuthPassword;
import net.schmizz.sshj.userauth.method.AuthPublickey;
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider;
import net.schmizz.sshj.userauth.password.PasswordUtils;
import net.schmizz.sshj.userauth.password.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Handles connections to SFTP servers.
 *
 * @author Maxence Bernard, Vassil Dichev
 */
class SFTPConnectionHandler extends ConnectionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SFTPConnectionHandler.class);

    /** Default port of the SSH protocol. */
    private static final int DEFAULT_PORT = 22;

    /**
     * The private keys a command line ssh client would offer when none was named, in the order it
     * tries them.
     */
    private static final String[] DEFAULT_KEY_NAMES = {"id_ed25519", "id_ecdsa", "id_rsa", "id_dsa"};

    SSHClient sshClient;
    SFTPClient sftpClient;

    SFTPConnectionHandler(FileURL location) {
        super(location);
    }

    @Override
    public void startConnection() throws IOException {
        LOGGER.info("starting connection to {}", realm);

        FileURL realm = getRealm();
        Credentials credentials = getCredentials();
        if (credentials == null || credentials.getLogin().isEmpty()) {
            throwAuthException("Login required");  // Todo: localize this entry
        }

        int port = realm.getPort() == -1 ? DEFAULT_PORT : realm.getPort();

        sshClient = new SSHClient();
        // Accepts any host key, which is what this client did before. Verifying against known_hosts
        // would be the safer choice, but needs a way for the user to confirm an unknown host first.
        sshClient.addHostKeyVerifier(new PromiscuousVerifier());
        sshClient.connect(realm.getHost(), port);

        // The agent is only needed while authenticating, so it is closed again right afterwards.
        try (SshAgent agent = openAgent()) {
            authenticate(credentials, agent);
        }

        sftpClient = sshClient.newSFTPClient();
    }

    /**
     * Opens the running ssh-agent, if there is one.
     *
     * @return the agent, <code>null</code> if none is reachable.
     */
    private static SshAgent openAgent() {
        try {
            return SshAgent.open();
        } catch (IOException e) {
            LOGGER.info("Cannot reach the ssh agent: {}", e.toString());
            return null;
        }
    }

    /**
     * Authenticates against the server, offering the methods in the order a command line ssh client
     * would: the agent first, then key files, and a password only as the last resort.
     *
     * @param  credentials the credentials to authenticate with.
     * @param  agent       the running agent, may be <code>null</code>.
     * @throws IOException if no method got us in.
     */
    private void authenticate(Credentials credentials, SshAgent agent) throws IOException {
        String login = credentials.getLogin();
        List<AuthMethod> methods = new ArrayList<>();

        // Keys held by the agent come first: they need no passphrase from us, which is the whole
        // point of running an agent.
        if (agent != null) {
            try {
                for (SshAgent.Identity identity : agent.getIdentities()) {
                    methods.add(new AgentAuthMethod(agent, identity));
                }
            } catch (IOException e) {
                LOGGER.info("Cannot read identities from the ssh agent: {}", e.toString());
            }
        }

        // Key files: the one configured for this server if there is one, the standard keys otherwise.
        for (String keyPath : keyCandidates()) {
            KeyProvider keyProvider = loadKey(keyPath, credentials.getPassword());
            if (keyProvider != null) {
                methods.add(new AuthPublickey(keyProvider));
            }
        }

        String password = credentials.getPassword();
        if (password != null && !password.isEmpty()) {
            methods.add(new AuthPassword(PasswordUtils.createOneOff(password.toCharArray())));
            methods.add(new AuthKeyboardInteractive(new PasswordResponseProvider(password)));
        }

        if (methods.isEmpty()) {
            throwAuthException("No authentication method available");  // Todo: localize this entry
        }

        try {
            sshClient.auth(login, methods);
            LOGGER.info("authenticated as {}", login);
        } catch (IOException e) {
            LOGGER.info("Authentication failed for {}", login, e);
            throwAuthException(e.getMessage());
        }
    }

    /**
     * Returns the key files to offer: the one configured for this server, or failing that the
     * standard keys found in the user's <code>.ssh</code> folder.
     *
     * @return the paths of the keys to try, in order.
     */
    private List<String> keyCandidates() {
        String configuredKey = realm.getProperty(SFTPFile.PRIVATE_KEY_PATH_PROPERTY_NAME);
        if (configuredKey != null && !configuredKey.isEmpty()) {
            return Collections.singletonList(configuredKey);
        }

        List<String> paths = new ArrayList<>();
        File sshFolder = new File(System.getProperty("user.home"), ".ssh");
        for (String name : DEFAULT_KEY_NAMES) {
            File key = new File(sshFolder, name);
            if (key.isFile() && key.canRead()) {
                paths.add(key.getAbsolutePath());
            }
        }
        return paths;
    }

    /**
     * Loads one private key, using the password as its passphrase where the key is encrypted.
     *
     * @param  keyPath    path of the key file.
     * @param  passphrase passphrase to try, may be <code>null</code>.
     * @return the key, <code>null</code> if it cannot be read or unlocked.
     */
    private KeyProvider loadKey(String keyPath, String passphrase) {
        try {
            return passphrase == null || passphrase.isEmpty()
                    ? sshClient.loadKeys(keyPath)
                    : sshClient.loadKeys(keyPath, passphrase.toCharArray());
        } catch (IOException e) {
            // An unreadable key or a wrong passphrase only rules out this one key; the remaining
            // candidates and finally password authentication still get their turn.
            LOGGER.info("Cannot use key {}: {}", keyPath, e.toString());
            return null;
        }
    }

    @Override
    public synchronized boolean isConnected() {
        return sshClient != null && sshClient.isConnected() && sshClient.isAuthenticated() && sftpClient != null;
    }

    @Override
    public synchronized void closeConnection() {
        if (sftpClient != null) {
            try {
                sftpClient.close();
            } catch (IOException e) {
                LOGGER.info("IOException caught while closing the SFTP client", e);
            }
            sftpClient = null;
        }
        if (sshClient != null) {
            try {
                sshClient.disconnect();
            } catch (IOException e) {
                LOGGER.info("IOException caught while disconnecting", e);
            }
            sshClient = null;
        }
    }

    @Override
    public void keepAlive() {
        // No-op, as before.
    }

    /**
     * Answers every keyboard-interactive prompt with the same password, which is how a server that
     * offers no plain password method is dealt with.
     */
    private static class PasswordResponseProvider implements ChallengeResponseProvider {

        private final String password;

        PasswordResponseProvider(String password) {
            this.password = password;
        }

        @Override
        public List<String> getSubmethods() {
            return Collections.emptyList();
        }

        @Override
        public void init(Resource resource, String name, String instruction) {
        }

        @Override
        public char[] getResponse(String prompt, boolean echo) {
            return password.toCharArray();
        }

        @Override
        public boolean shouldRetry() {
            return false;
        }
    }
}
