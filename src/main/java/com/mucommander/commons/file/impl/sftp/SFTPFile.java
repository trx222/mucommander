/*
 * This file is part of muCommander, http://www.mucommander.com
 * Copyright (C) 2002-2010 Maxence Bernard
 *
 * muCommander is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * muCommander is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.mucommander.commons.file.impl.sftp;

import com.mucommander.commons.file.*;
import com.mucommander.commons.file.connection.ConnectionHandler;
import com.mucommander.commons.file.connection.ConnectionPool;
import com.mucommander.commons.io.*;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.List;
import java.io.OutputStream;


/**
 * SFTPFile provides access to files located on an SFTP server.
 *
 * <p>The associated {@link FileURL} scheme is {@link FileProtocols#SFTP}. The host part of the URL designates the
 * SFTP server. Credentials must be specified in the login and password parts as SFTP servers require a login and
 * password. The path separator is <code>'/'</code>.
 *
 * <p>Here are a few examples of valid SFTP URLs:
 * <code>
 * sftp://server/pathto/somefile<br>
 * sftp://login:password@server/pathto/somefile<br>
 * </code>
 *
 * <p>Internally, SFTPFile uses {@link ConnectionPool} to create SFTP connections as needed and allows them to be
 * reused by SFTPFile instances located on the same server, dealing with concurrency issues. Connections are
 * thus managed transparently and need not be manually managed.
 *
 * <p>Low-level SFTP implementation is provided by the <code>J2SSH</code> library distributed under the LGPL license.
 *
 * @see ConnectionPool
 * @author Maxence Bernard
 */
public class SFTPFile extends ProtocolFile {
    private static final Logger LOGGER = LoggerFactory.getLogger(SFTPFile.class);

    /** The absolute path to the file on the remote server, not the full URL */
    private final String absPath;

    /** Contains the file attribute values */
    private final SFTPFileAttributes fileAttributes;

    /** Cached parent file instance, null if not created yet or if this file has no parent */
    private AbstractFile parent;
    /** Has the parent file been determined yet? */
    private boolean parentValSet;

    /** Cached canonical path value, null if the canonical path hasn't been fetched yet */
    private String canonicalPath;
    /** Timestamp when the canonical path value was fetched */
    private long canonicalPathFetchedTime;


    /** Period of time during which file attributes are cached, before being fetched again from the server. */
    private static long attributeCachingPeriod = 60000;

    /** a SFTPConnectionHandlerFactory instance */
    private final static SFTPConnectionHandlerFactory CONN_HANDLER_FACTORY = new SFTPConnectionHandlerFactory();

    /** Name of the property that holds the path to a private key. This property is optional; if it is set, private key
     * authentication is used. */
    public final static String PRIVATE_KEY_PATH_PROPERTY_NAME = "privateKeyPath";

    private final static String SEPARATOR = DEFAULT_SEPARATOR;


    /**
     * Creates a new instance of SFTPFile and initializes the SSH/SFTP connection to the server.
     * @throws IOException if an I/O error occurred
     */
    SFTPFile(FileURL fileURL) throws IOException {
        this(fileURL, null);
    }

    
    SFTPFile(FileURL fileURL, SFTPFileAttributes fileAttributes) throws IOException {
        super(fileURL);
//        // Throw an AuthException if the url doesn't contain any credentials
//        if(!fileURL.containsCredentials())
//            throw new AuthException(fileURL);

        this.absPath = fileURL.getPath();
        this.fileAttributes = fileAttributes == null ? new SFTPFileAttributes(fileURL) : fileAttributes;
    }

    /**
     * Sets the time period during which attributes values (e.g. isDirectory, last modified, ...) are cached.
     * The higher this value, the lower the number of network requests but also the longer it takes
     * before those attributes can be refreshed. A value of <code>0</code> disables attributes caching.
     *
     * <p>This class ensures that the attributes changed remotely by one of its methods are always updated locally, even
     * with attributes caching enabled. To illustrate, after a call to {@link #mkdir()}, {@link #isDirectory()} will
     * return <code>true</code>, even if the attributes haven't been refreshed. The attributes will however not be
     * consistent if they have been changed by another {@link SFTPFile} or by another process, and will remain
     * inconsistent for up to <code>period</code> milliseconds.
     *
     * @param period time period during which attributes values are cached, in milliseconds. 0 disables attributes caching.
     */
    public static void setAttributeCachingPeriod(long period) {
        attributeCachingPeriod = period;
    }

    private OutputStream getOutputStream(boolean append) throws IOException {
        // Retrieve a ConnectionHandler and lock it
        final SFTPConnectionHandler connHandler = (SFTPConnectionHandler)ConnectionPool.getConnectionHandler(CONN_HANDLER_FACTORY, fileURL, true);
        try {
            // Makes sure the connection is started, if not starts it
            connHandler.checkConnection();

            final RemoteFile remoteFile;
            if (exists()) {
                remoteFile = connHandler.sftpClient.open(absPath, append
                        ? EnumSet.of(OpenMode.WRITE, OpenMode.APPEND)
                        : EnumSet.of(OpenMode.WRITE, OpenMode.TRUNC));

                // Update local attributes
                if (!append) {
                    fileAttributes.setSize(0);
                }
            } else {
                // Set new file permissions to 644 octal (420 dec): "rw-r--r--"
                // Note: by default, permissions for files freshly created is 0 (not readable/writable/executable by anyone)!
                remoteFile = connHandler.sftpClient.open(absPath,
                        EnumSet.of(OpenMode.WRITE, OpenMode.CREAT),
                        new FileAttributes.Builder().withPermissions(0644).build());

                // Update local attributes
                fileAttributes.setExists(true);
                fileAttributes.setDate(System.currentTimeMillis());
                fileAttributes.setSize(0);
            }

            OutputStream os = remoteFile.new RemoteFileOutputStream(append ? getSize() : 0L) {
                @Override
                public void close() throws IOException {
                    super.close();
                    // The stream does not own the remote handle, so it is closed here
                    remoteFile.close();

                    // Release the lock on the ConnectionHandler
                    connHandler.releaseLock();
                }
            };
            ByteCounter byteCounter = new ByteCounter() {
                @Override
                public synchronized void add(long nbBytes) {
                    fileAttributes.addToSize(nbBytes);
                    fileAttributes.setDate(System.currentTimeMillis());
                }
            };
            return new CounterOutputStream(os, byteCounter);
        } catch(IOException e) {
            // Release the lock on the ConnectionHandler if the OutputStream could not be created
            connHandler.releaseLock();

            // Re-throw IOException
            throw e;
        }
    }


    public ConnectionHandler createConnectionHandler(FileURL location) {
        return new SFTPConnectionHandler(location);
    }


    /**
     * Implementation note: the value returned by this method will always be <code>false</code> if this file was
     * created by the public constructor. If this file was created by the private constructor (by {@link #ls()},
     * the value will be accurate (<code>true</code> if this file is a symlink) but will never get updated.
     * See {@link com.mucommander.commons.file.impl.sftp.SFTPFile.SFTPFileAttributes} for more information.
     */
    @Override
    public boolean isSymlink() {
        return fileAttributes.isSymlink();
    }

    @Override
    public boolean isSystem() {
        return false;
    }

    /**
     * Implementation note: for symlinks, returns the date of the link's target.
     */
    @Override
    public long getLastModifiedDate() {
        return ((SFTPFileAttributes)getCanonicalFile().getUnderlyingFileObject()).getLastModifiedDate();
    }

    @Override
    public void setLastModifiedDate(long lastModified) throws IOException {
        SFTPConnectionHandler connHandler = null;
        try {
            // Retrieve a ConnectionHandler and lock it
            connHandler = (SFTPConnectionHandler)ConnectionPool.getConnectionHandler(CONN_HANDLER_FACTORY, fileURL, true);

            // Makes sure the connection is started, if not starts it
            connHandler.checkConnection();

            // The access time is carried over, as setting attributes replaces both times at once
            FileAttributes current = connHandler.sftpClient.stat(absPath);
            connHandler.sftpClient.setattr(absPath, new FileAttributes.Builder()
                    .withAtimeMtime(current.getAtime(), lastModified / 1000)
                    .build());

            // Update local attribute copy
            fileAttributes.setDate(lastModified);
        } catch (IOException e) {
            LOGGER.error("failed to change the modification date of " + absPath, e);
            throw e;
        } finally {
            // Release the lock on the ConnectionHandler
            if (connHandler != null) {
                connHandler.releaseLock();
            }
        }
    }

    /**
     * Implementation note: for symlinks, returns the size of the link's target.
     */
    @Override
    public long getSize() {
        return ((SFTPFileAttributes)getCanonicalFile().getUnderlyingFileObject()).getSize();
    }
	
	
    @Override
    public AbstractFile getParent() {
        if(!parentValSet) {
            FileURL parentFileURL = this.fileURL.getParent();
            if (parentFileURL != null) {
                parent = FileFactory.getFile(parentFileURL);
                // Note: parent may be null if it can't be resolved
            }

            parentValSet = true;
        }
		
        return parent;
    }
	
	
    @Override
    public void setParent(AbstractFile parent) {
        this.parent = parent;
        this.parentValSet = true;
    }
	
	
    /**
     * Implementation note: for symlinks, returns the value of the link's target.
     */
    @Override
    public boolean exists() {
        return fileAttributes.exists();
    }

    /**
     * Implementation note: for symlinks, returns the permissions of the link's target.
     */
    @Override
    public FilePermissions getPermissions() {
        return ((SFTPFileAttributes)getCanonicalFile().getUnderlyingFileObject()).getPermissions();
    }

    @Override
    public PermissionBits getChangeablePermissions() {
        return PermissionBits.FULL_PERMISSION_BITS;     // Full permission support (777 octal)
    }

    @Override
    public void changePermission(int access, int permission, boolean enabled) throws IOException {
        changePermissions(ByteUtils.setBit(getPermissions().getIntValue(), (permission << (access*3)), enabled));
    }

    @Override
    public String getOwner() {
        return fileAttributes.getOwner();
    }

    @Override
    public boolean canGetOwner() {
        return true;
    }

    @Override
    public String getGroup() {
        return fileAttributes.getGroup();
    }

    @Override
    public boolean canGetGroup() {
        return true;
    }

    /**
     * Implementation note: for symlinks, returns the value of the link's target.
     */
    @Override
    public boolean isDirectory() {
        return ((SFTPFileAttributes)getCanonicalFile().getUnderlyingFileObject()).isDirectory();
    }
	
    @Override
    public InputStream getInputStream() throws IOException {
        return getInputStream(0);
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return getOutputStream(false);
    }

    @Override
    public OutputStream getAppendOutputStream() throws IOException {
        return getOutputStream(true);
    }

    @Override
    public RandomAccessInputStream getRandomAccessInputStream() throws IOException {
        return new SFTPRandomAccessInputStream();
    }

    @Override
    public void delete() throws IOException {
        // Retrieve a ConnectionHandler and lock it
        SFTPConnectionHandler connHandler = null;
        try {
            // Retrieve a ConnectionHandler and lock it
            connHandler = (SFTPConnectionHandler)ConnectionPool.getConnectionHandler(CONN_HANDLER_FACTORY, fileURL, true);

            // Makes sure the connection is started, if not starts it
            connHandler.checkConnection();

            if (isDirectory()) {
                connHandler.sftpClient.rmdir(absPath);
            } else {
                connHandler.sftpClient.rm(absPath);
            }

            // Update local attributes
            fileAttributes.setExists(false);
            fileAttributes.setDirectory(false);
            fileAttributes.setSymlink(false);
            fileAttributes.setSize(0);
        } finally {
            // Release the lock on the ConnectionHandler if the OutputStream could not be created
            if (connHandler != null) {
                connHandler.releaseLock();
            }
        }
    }


    @Override
    public AbstractFile[] ls() throws IOException {
        List<RemoteResourceInfo> files = getSftpFiles();
        int nbFiles = files.size();

        // File doesn't exist, return an empty file array
        if (nbFiles == 0) {
            return new AbstractFile[] {};
        }

        AbstractFile[] children = new AbstractFile[nbFiles];

        int fileCount = 0;
        String parentPath = fileURL.getPath();
        if (!parentPath.endsWith(SEPARATOR)) {
            parentPath += SEPARATOR;
        }

        // Fill AbstractFile array and discard '.' and '..' files
        for (RemoteResourceInfo file : files) {
            String filename = file.getName();
            // Discard '.' and '..' files, dunno why these are returned
            if (filename.equals(".") || filename.equals("..")) {
                continue;
            }

            FileURL childURL = (FileURL) fileURL.clone();
            childURL.setPath(parentPath + filename);

            children[fileCount++] = FileFactory.getFile(childURL, this, new SFTPFileAttributes(childURL, file.getAttributes()));
        }

        // create new array of the exact file count
        if (fileCount < nbFiles) {
            AbstractFile[] newChildren = new AbstractFile[fileCount];
            System.arraycopy(children, 0, newChildren, 0, fileCount);
            return newChildren;
        }

        return children;
    }

    private List<RemoteResourceInfo> getSftpFiles() throws IOException {
        // Retrieve a ConnectionHandler and lock it
        SFTPConnectionHandler connHandler = (SFTPConnectionHandler)ConnectionPool.getConnectionHandler(CONN_HANDLER_FACTORY, fileURL, true);
        try {
            connHandler.checkConnection();  // Makes sure the connection is started, if not starts it
            return connHandler.sftpClient.ls(absPath);
        } finally {
            // Release the lock on the ConnectionHandler
            connHandler.releaseLock();
        }
    }


    @Override
    public void mkdir() throws IOException {
        // Retrieve a ConnectionHandler and lock it
        SFTPConnectionHandler connHandler = (SFTPConnectionHandler)ConnectionPool.getConnectionHandler(CONN_HANDLER_FACTORY, fileURL, true);
        try {
            // Makes sure the connection is started, if not starts it
            connHandler.checkConnection();

            // Created with 755 octal (rwxr-xr-x): a directory created with the server's default would
            // otherwise end up with no permissions at all.
            connHandler.sftpClient.mkdir(absPath);
            connHandler.sftpClient.chmod(absPath, 0755);

            // Update local attributes
            fileAttributes.setExists(true);
            fileAttributes.setDirectory(true);
            fileAttributes.setDate(System.currentTimeMillis());
            fileAttributes.setSize(0);
        } finally {
            // Release the lock on the ConnectionHandler
            connHandler.releaseLock();
        }
    }

    /**
     * Implementation notes: server-to-server renaming will work if the destination file also uses the 'SFTP' scheme
     * and is located on the same host.
     */
    @Override
    public void renameTo(AbstractFile destFile) throws IOException {
        // Throw an exception if the file cannot be renamed to the specified destination.
        // Fail in situations where SFTPFile#renameTo() does not, for instance when the source and destination are the same.
        checkRenamePrerequisites(destFile, true, false);

        // Retrieve a ConnectionHandler and lock it
        SFTPConnectionHandler connHandler = null;
        try {
            connHandler = (SFTPConnectionHandler)ConnectionPool.getConnectionHandler(CONN_HANDLER_FACTORY, fileURL, true);

            // Makes sure the connection is started, if not starts it
            connHandler.checkConnection();

            // SftpClient#rename() throws an IOException if the destination exists (instead of overwriting the file)
            if (destFile.exists()) {
                destFile.delete();
            }

            // Will throw an IOException if the operation failed
            connHandler.sftpClient.rename(absPath, destFile.getURL().getPath());

            // Update destination file attributes by fetching them from the server
            ((SFTPFileAttributes)destFile.getUnderlyingFileObject()).fetchAttributes();

            // Update this file's attributes locally
            fileAttributes.setExists(false);
            fileAttributes.setDirectory(false);
            fileAttributes.setSize(0);
        } finally {
            // Release the lock on the ConnectionHandler
            if (connHandler != null) {
                connHandler.releaseLock();
            }
        }
    }

    /**
     * Returns a {@link com.mucommander.commons.file.impl.sftp.SFTPFile.SFTPFileAttributes} instance corresponding to this file.
     */
    @Override
    public Object getUnderlyingFileObject() {
        return fileAttributes;
    }


    // Unsupported file operations

    /**
     * Always throws an {@link UnsupportedFileOperationException}: random write access is not supported.
     */
    @Override
    @UnsupportedFileOperation
    public RandomAccessOutputStream getRandomAccessOutputStream() throws UnsupportedFileOperationException {
        throw new UnsupportedFileOperationException(FileOperation.RANDOM_WRITE_FILE);
    }

    /**
     * Always throws {@link UnsupportedFileOperationException} when called.
     *
     * @throws UnsupportedFileOperationException always
     */
    @Override
    @UnsupportedFileOperation
    public void copyRemotelyTo(AbstractFile destFile) throws UnsupportedFileOperationException {
        throw new UnsupportedFileOperationException(FileOperation.COPY_REMOTELY);
    }

    /**
     * Always throws {@link UnsupportedFileOperationException} when called.
     *
     * @throws UnsupportedFileOperationException always
     */
    @Override
    @UnsupportedFileOperation
    public long getFreeSpace() throws UnsupportedFileOperationException {
        // No way to retrieve this information with J2SSH
        throw new UnsupportedFileOperationException(FileOperation.GET_FREE_SPACE);
    }

    /**
     * Always throws {@link UnsupportedFileOperationException} when called.
     *
     * @throws UnsupportedFileOperationException always
     */
    @Override
    @UnsupportedFileOperation
    public long getTotalSpace() throws UnsupportedFileOperationException {
        // No way to retrieve this information with J2SSH
        throw new UnsupportedFileOperationException(FileOperation.GET_TOTAL_SPACE);
    }

    @Override
    @UnsupportedFileOperation
    public short getReplication() throws UnsupportedFileOperationException {
        throw new UnsupportedFileOperationException(FileOperation.GET_REPLICATION);
    }

    @Override
    @UnsupportedFileOperation
    public long getBlocksize() throws UnsupportedFileOperationException {
        throw new UnsupportedFileOperationException(FileOperation.GET_BLOCKSIZE);
    }

    @Override
    @UnsupportedFileOperation
    public void changeReplication(short replication) throws IOException {
        throw new UnsupportedFileOperationException(FileOperation.CHANGE_REPLICATION);
    }


    ////////////////////////
    // Overridden methods //
    ////////////////////////


    @Override
    public void changePermissions(int permissions) throws IOException {
        // Retrieve a ConnectionHandler and lock it
        SFTPConnectionHandler connHandler = null;
        try {
            connHandler = (SFTPConnectionHandler)ConnectionPool.getConnectionHandler(CONN_HANDLER_FACTORY, fileURL, true);

            // Makes sure the connection is started, if not starts it
            connHandler.checkConnection();

            connHandler.sftpClient.chmod(absPath, permissions);
            // Update local attribute copy
            fileAttributes.setPermissions(new SimpleFilePermissions(permissions));
        } finally {
            // Release the lock on the ConnectionHandler
            if (connHandler != null) {
                connHandler.releaseLock();
            }
        }
    }

    @Override
    public InputStream getInputStream(long offset) throws IOException {
        // Retrieve a ConnectionHandler and lock it
        final SFTPConnectionHandler connHandler = (SFTPConnectionHandler)ConnectionPool.getConnectionHandler(CONN_HANDLER_FACTORY, fileURL, true);
        try {
            // Makes sure the connection is started, if not starts it
            connHandler.checkConnection();

            final RemoteFile remoteFile = connHandler.sftpClient.open(absPath, EnumSet.of(OpenMode.READ));

            return remoteFile.new RemoteFileInputStream(offset) {
                @Override
                public void close() throws IOException {
                    super.close();
                    // The stream does not own the remote handle, so it is closed here
                    remoteFile.close();

                    // Release the lock on the ConnectionHandler
                    connHandler.releaseLock();
                }
            };
        } catch(IOException e) {
            // Release the lock on the ConnectionHandler if the InputStream could not be created
            connHandler.releaseLock();

            // Re-throw IOException
            throw e;
        }
    }

    @Override
    public String getCanonicalPath() {
        if (isSymlink()) {
            // Check if there is a previous value that hasn't expired yet
            if (canonicalPath != null && (System.currentTimeMillis() - canonicalPathFetchedTime < attributeCachingPeriod))
                return canonicalPath;

            SFTPConnectionHandler connHandler = null;
            try {
                // Retrieve a ConnectionHandler and lock it
                connHandler = (SFTPConnectionHandler)ConnectionPool.getConnectionHandler(CONN_HANDLER_FACTORY, fileURL, true);

                // Makes sure the connection is started, if not starts it
                connHandler.checkConnection();

                // getSymbolicLinkTarget returns the raw symlink target which can either be an absolute path or a
                // relative path. If the path is relative preprend the absolute path of the symlink's parent folder.
                String symlinkTargetPath = connHandler.sftpClient.readlink(fileURL.getPath());
                if (!symlinkTargetPath.startsWith("/")) {
                    String parentPath = fileURL.getParent().getPath();
                    if (!parentPath.endsWith("/")) {
                        parentPath += "/";
                    }
                    symlinkTargetPath = parentPath + symlinkTargetPath;
                }

                FileURL canonicalURL = (FileURL)fileURL.clone();
                canonicalURL.setPath(symlinkTargetPath);

                // Cache the value and return it until it expires
                canonicalPath = canonicalURL.toString(false);
                canonicalPathFetchedTime = System.currentTimeMillis();
                return canonicalPath;
            } catch(IOException e) {
                // Simply continue and return the absolute path
            } finally {
                // Release the lock on the ConnectionHandler
                if (connHandler != null) {
                    connHandler.releaseLock();
                }
            }
        }

        // If this file is not a symlink, or the symlink target path could not be retrieved, return the absolute path
        return getAbsolutePath();
    }

    /**
     * If the SFTPFile is a symbolic link, this method returns the name of the file being pointed to by the symbolic link.
     * @return The file pointed to by the symbolic link (null if the FTPFile is not a symbolic link).
     */
    public String getLink() {
        if (!isSymlink()) {
            return null;
        }
        String symlinkTargetPath;
        SFTPConnectionHandler connHandler = null;
        // Retrieve a ConnectionHandler and lock it
        try {
            connHandler = (SFTPConnectionHandler)ConnectionPool.getConnectionHandler(CONN_HANDLER_FACTORY, fileURL, true);
            // Makes sure the connection is started, if not starts it
            connHandler.checkConnection();
            // getSymbolicLinkTarget returns the raw symlink target which can either be an absolute path or a
            // relative path. If the path is relative preprend the absolute path of the symlink's parent folder.
            symlinkTargetPath = connHandler.sftpClient.readlink(fileURL.getPath());

        } catch (IOException e) {
            symlinkTargetPath = null;
            e.printStackTrace();
        } finally {
            // Release the lock on the ConnectionHandler
            if (connHandler != null) {
                connHandler.releaseLock();
            }
        }
        return symlinkTargetPath;
    }


    ///////////////////
    // Inner classes //
    ///////////////////

    /**
     * SFTPFileAttributes provides getters and setters for SFTP file attributes. By extending
     * <code>SyncedFileAttributes</code>, this class caches attributes for a  certain amount of time
     * ({@link SFTPFile#attributeCachingPeriod}) after which a fresh value is retrieved from the server.
     */
    static class SFTPFileAttributes extends SyncedFileAttributes {

        /** The URL pointing to the file whose attributes are cached by this class */
        private final FileURL url;

        /** True if the file is a symlink */
        private boolean isSymlink;

        // this constructor is called by SFTPFile public constructor
        private SFTPFileAttributes(FileURL url) throws AuthException {
            super(attributeCachingPeriod, false);       // no initial update

            this.url = url;
            setPermissions(FilePermissions.EMPTY_FILE_PERMISSIONS);

            fetchAttributes();      // throws AuthException if no or bad credentials

            updateExpirationDate(); // declare the attributes as 'fresh'
        }

        // this constructor is called by #ls()
        private SFTPFileAttributes(FileURL url, FileAttributes attrs) {
            super(attributeCachingPeriod, false);   // no initial update

            this.url = url;
            setPermissions(FilePermissions.EMPTY_FILE_PERMISSIONS);

            setAttributes(attrs);
            setExists(true);

            // Only the attributes listed by ls() tell a symlink apart, as they come from lstat and
            // therefore describe the link itself. stat() follows the link and reports its target, so
            // fetchAttributes() deliberately leaves this value alone: refreshing it would turn every
            // symlink into a plain file after the first update.
            this.isSymlink = attrs.getType() == net.schmizz.sshj.sftp.FileMode.Type.SYMLINK;

            updateExpirationDate(); // declare the attributes as 'fresh'
        }

        private void fetchAttributes() throws AuthException {
            SFTPConnectionHandler connHandler = null;
            try {
                // Retrieve a ConnectionHandler and lock it
                connHandler = (SFTPConnectionHandler)ConnectionPool.getConnectionHandler(SFTPFile.CONN_HANDLER_FACTORY, url, true);

                // Makes sure the connection is started, if not starts it
                connHandler.checkConnection();

                // Retrieve the file attributes from the server. This will throws an IOException if the file doesn't
                // exist on the server
                // Note for symlinks: the FileAttributes returned by SftpSubsystemClient#getAttributes(String)
                // returns the values of the symlink's target, not the symlink file itself. In other words: the size,
                // date, isDirectory, isLink values are those of the linked file. This is not a problem, except for
                // isLink because it makes impossible to detect changes in the isLink state. Changes should not happen
                // very often, but still.
                // Todo: try and fix for this in J2SSH
                setAttributes(connHandler.sftpClient.stat(url.getPath()));
                setExists(true);
            } catch (IOException e) {
                e.printStackTrace();
                // File doesn't exist on the server
                setExists(false);

                // Rethrow AuthException
                if (e instanceof AuthException) {
                    throw (AuthException) e;
                }
            } finally {
                // Release the lock on the ConnectionHandler
                if (connHandler != null) {
                    connHandler.releaseLock();
                }
            }
        }

        /**
         * Sets the file attributes using the values contained in the specified SFTP attributes.
         *
         * @param attrs the attributes reported by the server
         */
        private void setAttributes(FileAttributes attrs) {
            setDirectory(attrs.getType() == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY);
            // The server reports seconds, the file API works in milliseconds
            setDate(attrs.getMtime() * 1000);
            setSize(attrs.getSize());
            setPermissions(new SimpleFilePermissions(
               attrs.getMode().getPermissionsMask() & PermissionBits.FULL_PERMISSION_INT
            ));
            setOwner(String.valueOf(attrs.getUID()));
            setGroup(String.valueOf(attrs.getGID()));
            setSymlink(isSymlink);
        }

        /**
         * Increments the size attribute's value by the given number of bytes.
         *
         * @param increment number of bytes to add to the current size attribute's value
         */
        private void addToSize(long increment) {
            setSize(getSize()+increment);
        }

        /**
         * Returns <code>true</code> if the file is a symlink.
         *
         * @return <code>true</code> if the file is a symlink
         */
        private boolean isSymlink() {
            checkForExpiration(false);

            return isSymlink;
        }

        /**
         * Sets whether the file is a symlink.
         *
         * @param isSymlink <code>true</code> if the file is a symlink
         */
        private void setSymlink(boolean isSymlink) {
            this.isSymlink = isSymlink;
        }


        ////////////////////////////////////////////
        // SyncedFileAttributes implementation //
        ////////////////////////////////////////////

        @Override
        public void updateAttributes() {
            try {
                fetchAttributes();
            } catch(Exception e) {        // AuthException
                LOGGER.info("Failed to refresh attributes", e);
            }
        }
    }


    /**
     * SFTPRandomAccessInputStream extends RandomAccessInputStream to provide random read access to an SFTPFile.
     */
    private class SFTPRandomAccessInputStream extends RandomAccessInputStream {

        private final SFTPConnectionHandler connHandler;
        private final RemoteFile remoteFile;

        /** Read position, kept here as the remote handle is addressed by offset. */
        private long offset;

        private SFTPRandomAccessInputStream() throws IOException {
            this.connHandler = (SFTPConnectionHandler)ConnectionPool.getConnectionHandler(CONN_HANDLER_FACTORY, fileURL, true);
            try {
                // Makes sure the connection is started, if not starts it
                connHandler.checkConnection();
                this.remoteFile = connHandler.sftpClient.open(absPath, EnumSet.of(OpenMode.READ));
            } catch (IOException e) {
                // The lock is held until close(), so it has to be given back when opening fails
                connHandler.releaseLock();
                throw e;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = remoteFile.read(offset, b, off, len);
            if (read > 0) {
                offset += read;
            }
            return read;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : single[0] & 0xFF;
        }

        public long getOffset() {
            return offset;
        }

        public long getLength() {
            return getSize();
        }

        public void seek(long offset) {
            this.offset = offset;
        }

        @Override
        public void close() throws IOException {
            try {
                remoteFile.close();
            } finally {
                connHandler.releaseLock();
            }
        }
    }


//    private class SFTPProcess extends AbstractProcess {
//
//        private boolean success;
//        private SessionChannelClient sessionClient;
//        private SFTPConnectionHandler connHandler;
//
//        private SFTPProcess(String tokens[]) throws IOException {
//
//            try {
//                // Retrieve a ConnectionHandler and lock it
//                connHandler = (SFTPConnectionHandler)ConnectionPool.getConnectionHandler(CONN_HANDLER_FACTORY, fileURL, true);
//                // Makes sure the connection is started, if not starts it
//                connHandler.checkConnection();
//
//                sessionClient = connHandler.sshClient.openSessionChannel();
////                sessionClient.startShell();
//
////                success = sessionClient.executeCommand("cd "+(isDirectory()?fileURL.getPath():fileURL.getParent().getPath()));
////FileLogger.finest("commmand="+("cd "+(isDirectory()?fileURL.getPath():fileURL.getParent().getPath()))+" returned "+success);
//
//                // Environment variables are refused by most servers for security reasons
////                sessionClient.setEnvironmentVariable("cd", isDirectory()?fileURL.getPath():fileURL.getParent().getPath());
//
//                // No way to set the current working directory:
//                // 1/ when executing a single command:
//                //  + environment variables are ignored by most server, so can't use PWD for that.
//                //  + could send 'cd dir ; command' but it's not platform independant and prevents the command from being
//                //    executed under Windows
//                // 2/ when starting a shell, no problem to change the current working directory (cd dir\n is sent before
//                // the command), but there is no reliable way to detect the end of the command execution, as confirmed
//                // by one of the J2SSH developers : http://sourceforge.net/forum/message.php?msg_id=1826569
//
//                // Concatenates all tokens to create the command string
//                StringBuffer command = new StringBuffer();
//                int nbTokens = tokens.length;
//                for(int i=0; i<nbTokens; i++) {
//                    command.append(tokens[i]);
//                    if(i!=nbTokens-1)
//                        command.append(" ");
//                }
//
//                success = sessionClient.executeCommand(command.toString());
//                FileLogger.finest("commmand="+command+" returned "+success);
//            }
//            catch(IOException e) {
//                // Release the lock on the ConnectionHandler
//                connHandler.releaseLock();
//
//                sessionClient.close();
//
//                // Re-throw exception
//                throw e;
//            }
//        }
//
//        public boolean usesMergedStreams() {
//            return false;
//        }
//
//        public int waitFor() throws InterruptedException, IOException {
//            return sessionClient.getExitCode().intValue();
//        }
//
//        protected void destroyProcess() throws IOException {
//            // Release the lock on the ConnectionHandler
//            connHandler.releaseLock();
//
//            sessionClient.close();
//        }
//
//        public int exitValue() {
//            return sessionClient.getExitCode().intValue();
//        }
//
//        public OutputStream getOutputStream() throws IOException {
//            return sessionClient.getOutputStream();
//        }
//
//        public InputStream getInputStream() throws IOException {
//            return sessionClient.getInputStream();
//        }
//
//        public InputStream getErrorStream() throws IOException {
//            return sessionClient.getStderrInputStream();
//        }
//    }
}
