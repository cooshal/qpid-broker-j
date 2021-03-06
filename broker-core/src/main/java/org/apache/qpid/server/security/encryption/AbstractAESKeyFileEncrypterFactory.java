/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.server.security.encryption;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.server.configuration.IllegalConfigurationException;
import org.apache.qpid.server.model.ConfiguredObject;
import org.apache.qpid.server.model.SystemConfig;
import org.apache.qpid.server.plugin.ConditionallyAvailable;
import org.apache.qpid.server.plugin.ConfigurationSecretEncrypterFactory;

abstract class AbstractAESKeyFileEncrypterFactory implements ConfigurationSecretEncrypterFactory, ConditionallyAvailable
{

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractAESKeyFileEncrypterFactory.class);

    static final String ENCRYPTER_KEY_FILE = "encrypter.key.file";

    private static final int AES_KEY_SIZE_BITS = 256;
    private static final int AES_KEY_SIZE_BYTES = AES_KEY_SIZE_BITS / 8;
    private static final String AES_ALGORITHM = "AES";

    static final String DEFAULT_KEYS_SUBDIR_NAME = ".keys";

    private static final boolean IS_AVAILABLE;

    private static final String GENERAL_EXCEPTION_MESSAGE =
            "Unable to determine a mechanism to protect access to the key file on this filesystem";

    static
    {
        boolean isAvailable;
        try
        {
            final int allowedKeyLength = Cipher.getMaxAllowedKeyLength(AES_ALGORITHM);
            isAvailable = allowedKeyLength >= AES_KEY_SIZE_BITS;
            if (!isAvailable)
            {
                LOGGER.info("The {} configuration encryption encryption mechanism is not available. "
                            + "Maximum available AES key length is {} but {} is required. "
                            + "Ensure the full strength JCE policy has been installed into your JVM.",
                            AES_ALGORITHM,
                            allowedKeyLength,
                            AES_KEY_SIZE_BITS);
            }
        }
        catch (NoSuchAlgorithmException e)
        {
            isAvailable = false;

            LOGGER.error("The {} configuration encryption mechanism is not available. "
                         + "The {} algorithm is not available within the JVM (despite it being a requirement).",
                         AES_ALGORITHM,
                         AES_ALGORITHM);
        }

        IS_AVAILABLE = isAvailable;
    }

    @Override
    public ConfigurationSecretEncrypter createEncrypter(final ConfiguredObject<?> object)
    {
        final String fileLocation = getSecretKeyLocation(object);
        final File file = new File(fileLocation);
        if (!file.exists())
        {
            LOGGER.info(
                    "Configuration encryption is enabled, but no configuration secret was found. "
                    + "A new configuration secret will be created at '{}'.",
                    fileLocation);
            createAndPopulateKeyFile(file);
        }
        if (!file.isFile())
        {
            throw new IllegalConfigurationException(String.format("File '%s' is not a regular file.", fileLocation));
        }
        try
        {
            checkFilePermissions(fileLocation, file);
            if (Files.size(file.toPath()) != AES_KEY_SIZE_BYTES)
            {
                throw new IllegalConfigurationException(String.format(
                        "Key file '%s' contains an incorrect about of data",
                        fileLocation));
            }

            try (final FileInputStream inputStream = new FileInputStream(file))
            {
                final byte[] key = new byte[AES_KEY_SIZE_BYTES];
                int pos = 0;
                int read;
                while (pos < key.length && -1 != (read = inputStream.read(key, pos, key.length - pos)))
                {
                    pos += read;
                }
                if (pos != key.length)
                {
                    throw new IllegalConfigurationException(String.format(
                            "Key file '%s' contained an incorrect about of data",
                            fileLocation));
                }
                SecretKeySpec keySpec = new SecretKeySpec(key, AES_ALGORITHM);
                return createEncrypter(keySpec);
            }
        }
        catch (IOException e)
        {
            throw new IllegalConfigurationException(String.format("Unable to get file permissions: %s", e.getMessage()),
                                                    e);
        }
    }

    static String getSecretKeyLocation(final ConfiguredObject<?> object)
    {
        final String fileLocation;
        if (object.getContextKeys(false).contains(ENCRYPTER_KEY_FILE))
        {
            fileLocation = object.getContextValue(String.class, ENCRYPTER_KEY_FILE);
        }
        else
        {

            fileLocation = object.getContextValue(String.class, SystemConfig.QPID_WORK_DIR)
                           + File.separator + DEFAULT_KEYS_SUBDIR_NAME + File.separator
                           + object.getCategoryClass().getSimpleName() + "_"
                           + object.getName() + ".key";

            final Map<String, String> modifiedContext = new LinkedHashMap<>(object.getContext());
            modifiedContext.put(ENCRYPTER_KEY_FILE, fileLocation);
            object.setAttributes(Collections.singletonMap(ConfiguredObject.CONTEXT, modifiedContext));
        }
        return fileLocation;
    }

    private void checkFilePermissions(String fileLocation, File file) throws IOException
    {
        if (isPosixFileSystem(file))
        {
            final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file.toPath());
            if (permissions.contains(PosixFilePermission.GROUP_READ)
                || permissions.contains(PosixFilePermission.OTHERS_READ)
                || permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE))
            {
                throw new IllegalArgumentException(String.format(
                        "Key file '%s' has incorrect permissions.  Only the owner "
                        + "should be able to read or write this file.",
                        fileLocation));
            }
        }
        else if (isAclFileSystem(file))
        {
            final AclFileAttributeView attributeView =
                    Files.getFileAttributeView(file.toPath(), AclFileAttributeView.class);
            final ArrayList<AclEntry> acls = new ArrayList<>(attributeView.getAcl());
            final ListIterator<AclEntry> iter = acls.listIterator();
            final UserPrincipal owner = Files.getOwner(file.toPath());
            while (iter.hasNext())
            {
                final AclEntry acl = iter.next();
                if (acl.type() == AclEntryType.ALLOW)
                {
                    final Set<AclEntryPermission> originalPermissions = acl.permissions();
                    final Set<AclEntryPermission> updatedPermissions = EnumSet.copyOf(originalPermissions);

                    if (updatedPermissions.removeAll(EnumSet.of(AclEntryPermission.APPEND_DATA,
                                                                AclEntryPermission.EXECUTE,
                                                                AclEntryPermission.WRITE_ACL,
                                                                AclEntryPermission.WRITE_DATA,
                                                                AclEntryPermission.WRITE_OWNER)))
                    {
                        throw new IllegalArgumentException(
                                String.format("Key file '%s has incorrect permissions. "
                                              + "The file should not be modifiable by any user.",
                                              fileLocation));
                    }
                    if (!owner.equals(acl.principal())
                        && updatedPermissions.removeAll(EnumSet.of(AclEntryPermission.READ_DATA)))
                    {
                        throw new IllegalArgumentException(
                                String.format(
                                        "Key file '%s' has incorrect permissions. "
                                        + "Only the owner should be able to read from the file.",
                                        fileLocation));
                    }
                }
            }
        }
        else
        {
            throw new IllegalArgumentException(GENERAL_EXCEPTION_MESSAGE);
        }
    }

    private static boolean isPosixFileSystem(File file)
    {
        return Files.getFileAttributeView(file.toPath(), PosixFileAttributeView.class) != null;
    }

    private static boolean isAclFileSystem(File file)
    {
        return Files.getFileAttributeView(file.toPath(), AclFileAttributeView.class) != null;
    }


    static void createAndPopulateKeyFile(final File file)
    {
        try
        {
            createEmptyKeyFile(file);

            final KeyGenerator keyGenerator = KeyGenerator.getInstance(AES_ALGORITHM);
            keyGenerator.init(AES_KEY_SIZE_BITS);
            final SecretKey key = keyGenerator.generateKey();
            try (final FileOutputStream os = new FileOutputStream(file))
            {
                os.write(key.getEncoded());
            }

            makeKeyFileReadOnly(file);
        }
        catch (NoSuchAlgorithmException | IOException e)
        {
            throw new IllegalConfigurationException(String.format("Cannot create key file: %s", e.getMessage()), e);
        }
    }

    private static void makeKeyFileReadOnly(File file) throws IOException
    {
        if (isPosixFileSystem(file))
        {
            Files.setPosixFilePermissions(file.toPath(), EnumSet.of(PosixFilePermission.OWNER_READ));
        }
        else if (isAclFileSystem(file))
        {
            final AclFileAttributeView attributeView = Files.getFileAttributeView(file.toPath(), AclFileAttributeView.class);
            final ArrayList<AclEntry> acls = new ArrayList<>(attributeView.getAcl());
            final ListIterator<AclEntry> iter = acls.listIterator();
            file.setReadOnly();
            while (iter.hasNext())
            {
                final AclEntry acl = iter.next();
                final Set<AclEntryPermission> originalPermissions = acl.permissions();
                final Set<AclEntryPermission> updatedPermissions = EnumSet.copyOf(originalPermissions);

                if (updatedPermissions.removeAll(EnumSet.of(AclEntryPermission.APPEND_DATA,
                                                            AclEntryPermission.DELETE,
                                                            AclEntryPermission.EXECUTE,
                                                            AclEntryPermission.WRITE_ACL,
                                                            AclEntryPermission.WRITE_DATA,
                                                            AclEntryPermission.WRITE_ATTRIBUTES,
                                                            AclEntryPermission.WRITE_NAMED_ATTRS,
                                                            AclEntryPermission.WRITE_OWNER)))
                {
                    final AclEntry.Builder builder = AclEntry.newBuilder(acl);
                    builder.setPermissions(updatedPermissions);
                    iter.set(builder.build());
                }
            }
            attributeView.setAcl(acls);
        }
        else
        {
            throw new IllegalConfigurationException(GENERAL_EXCEPTION_MESSAGE);
        }
    }

    private static void createEmptyKeyFile(File file) throws IOException
    {
        final Path parentFilePath = file.getAbsoluteFile().getParentFile().toPath();

        if (isPosixFileSystem(file))
        {
            final Set<PosixFilePermission> ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ,
                                                            PosixFilePermission.OWNER_WRITE,
                                                            PosixFilePermission.OWNER_EXECUTE);
            Files.createDirectories(parentFilePath, PosixFilePermissions.asFileAttribute(ownerOnly));

            Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        }
        else if (isAclFileSystem(file))
        {
            Files.createDirectories(parentFilePath);
            final UserPrincipal owner = Files.getOwner(parentFilePath);
            final AclFileAttributeView attributeView = Files.getFileAttributeView(parentFilePath, AclFileAttributeView.class);
            final List<AclEntry> acls = new ArrayList<>(attributeView.getAcl());
            final ListIterator<AclEntry> iter = acls.listIterator();
            boolean found = false;
            while (iter.hasNext())
            {
                final AclEntry acl = iter.next();
                if (!owner.equals(acl.principal()))
                {
                    iter.remove();
                }
                else if (acl.type() == AclEntryType.ALLOW)
                {
                    found = true;
                    final AclEntry.Builder builder = AclEntry.newBuilder(acl);
                    final Set<AclEntryPermission> permissions = acl.permissions().isEmpty()
                            ? new HashSet<>()
                            : EnumSet.copyOf(acl.permissions());
                    permissions.addAll(Arrays.asList(AclEntryPermission.ADD_FILE,
                                                     AclEntryPermission.ADD_SUBDIRECTORY,
                                                     AclEntryPermission.LIST_DIRECTORY));
                    builder.setPermissions(permissions);
                    iter.set(builder.build());
                }
            }
            if (!found)
            {
                final AclEntry.Builder builder = AclEntry.newBuilder();
                builder.setPermissions(AclEntryPermission.ADD_FILE,
                                       AclEntryPermission.ADD_SUBDIRECTORY,
                                       AclEntryPermission.LIST_DIRECTORY);
                builder.setType(AclEntryType.ALLOW);
                builder.setPrincipal(owner);
                acls.add(builder.build());
            }
            attributeView.setAcl(acls);

            Files.createFile(file.toPath(), new FileAttribute<List<AclEntry>>()
            {
                @Override
                public String name()
                {
                    return "acl:acl";
                }

                @Override
                public List<AclEntry> value()
                {
                    final AclEntry.Builder builder = AclEntry.newBuilder();
                    builder.setType(AclEntryType.ALLOW);
                    builder.setPermissions(EnumSet.allOf(AclEntryPermission.class));
                    builder.setPrincipal(owner);
                    return Collections.singletonList(builder.build());
                }
            });
        }
        else
        {
            throw new IllegalConfigurationException(GENERAL_EXCEPTION_MESSAGE);
        }
    }

    @Override
    public boolean isAvailable()
    {
        return IS_AVAILABLE;
    }

    protected abstract ConfigurationSecretEncrypter createEncrypter(final SecretKeySpec keySpec);
}
