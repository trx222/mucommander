/*
 * This file is part of trolCommander, http://www.trolsoft.ru/en/soft/trolcommander
 * Copyright (C) 2013-2025 Oleg Trifonov
 *
 * trolCommander is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * trolCommander is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.mucommander.commons.file.impl.sftp;

import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.SSHPacket;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.method.AbstractAuthMethod;

import java.io.IOException;

/** Public key authentication whose signing is delegated to a running ssh-agent. */
public class AgentAuthMethod extends AbstractAuthMethod {

    private final SshAgent agent;
    private final SshAgent.Identity identity;

    /** Signature algorithm announced to the server, which need not equal the key's own type. */
    private final String algorithm;
    private final int signFlags;

    public AgentAuthMethod(SshAgent agent, SshAgent.Identity identity) {
        super("publickey");
        this.agent = agent;
        this.identity = identity;

        // OpenSSH 8.8 and newer refuse SHA-1 signatures, so an RSA key is offered as rsa-sha2-512
        // and the agent is asked for a matching signature.
        if ("ssh-rsa".equals(identity.type)) {
            this.algorithm = "rsa-sha2-512";
            this.signFlags = SshAgent.FLAG_RSA_SHA2_512;
        } else {
            this.algorithm = identity.type;
            this.signFlags = 0;
        }
    }

    @Override
    protected SSHPacket buildReq() throws UserAuthException {
        SSHPacket req = super.buildReq()
                .putBoolean(true)
                .putString(algorithm)
                .putBytes(identity.blob);

        // RFC 4252: the signature covers the session identifier followed by this very request.
        byte[] data = new Buffer.PlainBuffer()
                .putString(params.getTransport().getSessionID())
                .putBuffer(req)
                .getCompactData();
        try {
            return req.putBytes(agent.sign(identity.blob, data, signFlags));
        } catch (IOException e) {
            throw new UserAuthException("agent failed to sign", e);
        }
    }

    @Override
    public String toString() {
        return "publickey via agent (" + algorithm + ", " + identity.comment + ")";
    }
}
