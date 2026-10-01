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

import java.io.Closeable;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal ssh-agent client speaking the agent protocol over $SSH_AUTH_SOCK. */
public class SshAgent implements Closeable {

    private static final byte REQUEST_IDENTITIES = 11;
    private static final byte IDENTITIES_ANSWER  = 12;
    private static final byte SIGN_REQUEST       = 13;
    private static final byte SIGN_RESPONSE      = 14;

    /** Ask for a SHA-2 signature; SHA-1 is refused by OpenSSH 8.8 and newer. */
    public static final int FLAG_RSA_SHA2_256 = 2;
    public static final int FLAG_RSA_SHA2_512 = 4;

    public static class Identity {
        public final byte[] blob;
        public final String comment;
        public final String type;
        Identity(byte[] blob, String comment) {
            this.blob = blob;
            this.comment = comment;
            this.type = new String(readString(ByteBuffer.wrap(blob)));
        }
    }

    private final SocketChannel channel;

    private SshAgent(SocketChannel channel) {
        this.channel = channel;
    }

    public static SshAgent open() throws IOException {
        String socket = System.getenv("SSH_AUTH_SOCK");
        if (socket == null || socket.isEmpty()) {
            return null;
        }
        return new SshAgent(SocketChannel.open(UnixDomainSocketAddress.of(socket)));
    }

    public List<Identity> getIdentities() throws IOException {
        ByteBuffer reply = request(ByteBuffer.allocate(1).put(REQUEST_IDENTITIES));
        if (reply.get() != IDENTITIES_ANSWER) {
            throw new IOException("unexpected agent reply to identities request");
        }
        int count = reply.getInt();
        List<Identity> identities = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] blob = readString(reply);
            identities.add(new Identity(blob, new String(readString(reply))));
        }
        return identities;
    }

    public byte[] sign(byte[] keyBlob, byte[] data, int flags) throws IOException {
        ByteBuffer out = ByteBuffer.allocate(1 + 4 + keyBlob.length + 4 + data.length + 4);
        out.put(SIGN_REQUEST);
        out.putInt(keyBlob.length).put(keyBlob);
        out.putInt(data.length).put(data);
        out.putInt(flags);

        ByteBuffer reply = request(out);
        if (reply.get() != SIGN_RESPONSE) {
            throw new IOException("agent refused to sign");
        }
        return readString(reply);
    }

    /** Sends one request and reads the length-prefixed reply. */
    private ByteBuffer request(ByteBuffer payload) throws IOException {
        payload.flip();
        ByteBuffer out = ByteBuffer.allocate(4 + payload.remaining());
        out.putInt(payload.remaining()).put(payload).flip();
        while (out.hasRemaining()) {
            channel.write(out);
        }

        ByteBuffer header = readFully(ByteBuffer.allocate(4));
        return readFully(ByteBuffer.allocate(header.getInt()));
    }

    private ByteBuffer readFully(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("agent closed the connection");
            }
        }
        return buffer.flip();
    }

    private static byte[] readString(ByteBuffer buffer) {
        byte[] value = new byte[buffer.getInt()];
        buffer.get(value);
        return value;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
