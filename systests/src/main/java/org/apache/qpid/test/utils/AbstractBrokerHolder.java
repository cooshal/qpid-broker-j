/*
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
 */

package org.apache.qpid.test.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.server.BrokerOptions;
import org.apache.qpid.util.FileUtils;

public abstract class AbstractBrokerHolder implements BrokerHolder
{
    private static final String RELATIVE_BROKER_CONFIG_PATH = "config.json";
    private static final AtomicInteger BROKER_INDEX = new AtomicInteger();
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractBrokerHolder.class);

    private final String _classQualifiedTestName;
    private final File _logFile;
    private final String _brokerStoreType;
    private final String _configurationPath;
    private final int _brokerIndex;
    private final String _amqpTcpPortRegExp;
    private final String _initialConfigurationPath;
    private final String _httpTcpPortRegExp;
    private final String _amqpTlsPortRegExp;
    private final String _httpTlsPortRegExp;
    private final Path _qpidWorkDir;

    private TestBrokerConfiguration _configuration;
    private int _amqpPort;
    private int _httpPort;
    private int _amqpTlsPort;
    private int _httpsPort;

    public AbstractBrokerHolder(int port, String classQualifiedTestName, File logFile)
    {
        _amqpPort = port;
        _brokerIndex = BROKER_INDEX.getAndIncrement();
        _classQualifiedTestName = classQualifiedTestName;
        _logFile = logFile;
        _initialConfigurationPath = System.getProperty("broker.config");
        _brokerStoreType = System.getProperty("broker.config-store-type", "JSON");
        _amqpTcpPortRegExp =
                System.getProperty("broker.amqpTcpPortRegEx", "BRK-1002 : Starting : Listening on TCP port (\\d+)");
        _httpTcpPortRegExp = System.getProperty("broker.httpTcpPortRegEx",
                                                "MNG-1002 : Starting : HTTP : Listening on TCP port (\\d+)");
        _amqpTlsPortRegExp =
                System.getProperty("broker.amqpTlsPortRegEx", "BRK-1002 : Starting : Listening on SSL port (\\d+)");
        _httpTlsPortRegExp = System.getProperty("broker.httpTlsPortRegEx",
                                                "MNG-1002 : Starting : HTTP : Listening on SSL port (\\d+)");

        try
        {
            _qpidWorkDir = Files.createTempDirectory("qpid-work-" + _classQualifiedTestName + "-" + _brokerIndex + "-");
            _configurationPath = _qpidWorkDir.toAbsolutePath().resolve(RELATIVE_BROKER_CONFIG_PATH).toString();
        }
        catch (IOException e)
        {
            throw new RuntimeException("Cannot create work directory", e);
        }
    }

    @Override
    public Path getWorkDir()
    {
        return _qpidWorkDir;
    }

    @Override
    public String getConfigurationPath()
    {
        return _configurationPath;
    }

    @Override
    public int getBrokerIndex()
    {
        return _brokerIndex;
    }

    @Override
    public int getAmqpPort()
    {
        return _amqpPort;
    }

    @Override
    public int getHttpPort()
    {
        return _httpPort;
    }

    @Override
    public int getHttpsPort()
    {
        return _httpsPort;
    }

    @Override
    public int getAmqpTlsPort()
    {
        return _amqpTlsPort;
    }

    @Override
    public void cleanUp()
    {
        getConfiguration().cleanUp();
        FileUtils.delete(getWorkDir().toFile(), true);
    }

    @Override
    public TestBrokerConfiguration getConfiguration()
    {
        if (_configuration == null)
        {
            _configuration = createBrokerConfiguration();
        }
        return _configuration;
    }

    @Override
    public void restart() throws Exception
    {
        shutdown();
        start();
    }

    @Override
    public void start() throws Exception
    {
        start(false);
    }

    @Override
    public void start(boolean managementMode) throws Exception
    {
        saveConfiguration(getConfiguration());
        BrokerOptions options = createBrokerOptions(managementMode, _amqpPort);
        start(options);
        if (_amqpPort <= 0)
        {
            _amqpPort = scrubPortFromLog(_logFile, _amqpTcpPortRegExp);
        }
        if (_amqpTlsPort <= 0)
        {
            _amqpTlsPort = scrubPortFromLog(_logFile, _amqpTlsPortRegExp);
        }
        if (_httpPort <= 0)
        {
            _httpPort = scrubPortFromLog(_logFile, _httpTcpPortRegExp);
        }
        if (_httpsPort <= 0)
        {
            _httpsPort = scrubPortFromLog(_logFile, _httpTlsPortRegExp);
        }
    }

    @Override
    public void createVirtualHostNode(final String virtualHostNodeName,
                                      final String storeType,
                                      final String storeDir,
                                      final String blueprint)
    {
        getConfiguration().createVirtualHostNode(virtualHostNodeName, storeType, storeDir, blueprint);
    }

    protected TestBrokerConfiguration createBrokerConfiguration()
    {
        return new TestBrokerConfiguration(_brokerStoreType, new File(_initialConfigurationPath).getAbsolutePath());
    }

    protected String getClassQualifiedTestName()
    {
        return _classQualifiedTestName;
    }

    protected void waitUntilPortsAreFree()
    {
        Set<Integer> ports = new HashSet<>();
        ports.add(getAmqpPort());
        ports.add(getAmqpTlsPort());
        ports.add(getHttpPort());
        ports.add(getHttpsPort());
        new PortHelper().waitUntilPortsAreFree(ports);
    }

    abstract protected String getLogPrefix();

    abstract void start(final BrokerOptions options) throws Exception;

    private void saveConfiguration(TestBrokerConfiguration testConfiguration)
    {
        if (testConfiguration != null && !testConfiguration.isSaved())
        {
            LOGGER.info("Saving test broker configuration at: " + _configurationPath);
            testConfiguration.save(new File(_configurationPath));
            testConfiguration.setSaved(true);
        }
    }

    private BrokerOptions createBrokerOptions(boolean managementMode, int amqpPort)
    {
        BrokerOptions options = new BrokerOptions();

        options.setConfigProperty("qpid.work_dir", getWorkDir().toString());
        options.setConfigProperty("test.port", String.valueOf(amqpPort));
        options.setConfigProperty("test.hport", String.valueOf(getHttpPort()));
        options.setConfigurationStoreType(_brokerStoreType);
        options.setConfigurationStoreLocation(_configurationPath);
        options.setManagementMode(managementMode);
        if (managementMode)
        {
            options.setManagementModePassword(QpidBrokerTestCase.MANAGEMENT_MODE_PASSWORD);
        }
        options.setStartupLoggedToSystemOut(false);
        return options;
    }

    private int scrubPortFromLog(final File logFile, final String portRegEx) throws IOException
    {
        final String logPrefix = getLogPrefix();
        Pattern portPattern = Pattern.compile(portRegEx);
        try (BufferedReader br = new BufferedReader(new FileReader(logFile)))
        {
            String line = null;
            while ((line = br.readLine()) != null)
            {
                if (logPrefix == null || line.contains(logPrefix))
                {
                    Matcher matcher = portPattern.matcher(line);
                    if (matcher.find() && matcher.groupCount() > 0)
                    {
                        return Integer.valueOf(matcher.group(1)).intValue();
                    }
                }
            }
        }
        return 0;
    }

}