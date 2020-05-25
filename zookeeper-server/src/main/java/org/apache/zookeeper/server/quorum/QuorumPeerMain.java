/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zookeeper.server.quorum;

import java.io.IOException;
import java.util.Properties;

import javax.management.JMException;
import javax.security.sasl.SaslException;

import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.jmx.ManagedUtil;
import org.apache.zookeeper.metrics.MetricsProvider;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.apache.zookeeper.metrics.impl.MetricsProviderBootstrap;
import org.apache.zookeeper.server.ExitCode;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.DatadirCleanupManager;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.server.admin.AdminServer.AdminServerException;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog.DatadirException;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;

/**
 *
 * <h2>Configuration file</h2>
 *
 * When the main() method of this class is used to start the program, the first
 * argument is used as a path to the config file, which will be used to obtain
 * configuration information. This file is a Properties file, so keys and
 * values are separated by equals (=) and the key/value pairs are separated
 * by new lines. The following is a general summary of keys used in the
 * configuration file. For full details on this see the documentation in
 * docs/index.html
 * <ol>
 * <li>dataDir - The directory where the ZooKeeper data is stored.</li>
 * <li>dataLogDir - The directory where the ZooKeeper transaction log is stored.</li>
 * <li>clientPort - The port used to communicate with clients.</li>
 * <li>tickTime - The duration of a tick in milliseconds. This is the basic
 * unit of time in ZooKeeper.</li>
 * <li>initLimit - The maximum number of ticks that a follower will wait to
 * initially synchronize with a leader.</li>
 * <li>syncLimit - The maximum number of ticks that a follower will wait for a
 * message (including heartbeats) from the leader.</li>
 * <li>server.<i>id</i> - This is the host:port[:port] that the server with the
 * given id will use for the quorum protocol.</li>
 * </ol>
 * In addition to the config file. There is a file in the data directory called
 * "myid" that contains the server id as an ASCII decimal value.
 *
 * 集群启动模式
 */
@InterfaceAudience.Public
public class QuorumPeerMain {
    private static final Logger LOG = LoggerFactory.getLogger(QuorumPeerMain.class);

    private static final String USAGE = "Usage: QuorumPeerMain configfile";

    protected QuorumPeer quorumPeer;

    /**
     * To start the replicated server specify the configuration file name on
     * the command line.
     * @param args path to the configfile
     */
    public static void main(String[] args) {
        //集群模式主类
        QuorumPeerMain main = new QuorumPeerMain();
        try {
            //很简单的初始化,并运行(进入)
            main.initializeAndRun(args);
        } catch (IllegalArgumentException e) {
            LOG.error("Invalid arguments, exiting abnormally", e);
            LOG.info(USAGE);
            System.err.println(USAGE);
            System.exit(ExitCode.INVALID_INVOCATION.getValue());
        } catch (ConfigException e) {
            LOG.error("Invalid config, exiting abnormally", e);
            System.err.println("Invalid config, exiting abnormally");
            System.exit(ExitCode.INVALID_INVOCATION.getValue());
        } catch (DatadirException e) {
            LOG.error("Unable to access datadir, exiting abnormally", e);
            System.err.println("Unable to access datadir, exiting abnormally");
            System.exit(ExitCode.UNABLE_TO_ACCESS_DATADIR.getValue());
        } catch (AdminServerException e) {
            LOG.error("Unable to start AdminServer, exiting abnormally", e);
            System.err.println("Unable to start AdminServer, exiting abnormally");
            System.exit(ExitCode.ERROR_STARTING_ADMIN_SERVER.getValue());
        } catch (Exception e) {
            LOG.error("Unexpected exception, exiting abnormally", e);
            System.exit(ExitCode.UNEXPECTED_ERROR.getValue());
        }
        LOG.info("Exiting normally");
        System.exit(ExitCode.EXECUTION_FINISHED.getValue());
    }

    //开始解读
    protected void initializeAndRun(String[] args)
        throws ConfigException, IOException, AdminServerException
    {
        QuorumPeerConfig config = new QuorumPeerConfig();
        if (args.length == 1) {
            config.parse(args[0]);
        }

        // Start and schedule the the purge task
        //数据目录清理管理器
        DatadirCleanupManager purgeMgr = new DatadirCleanupManager(config
                .getDataDir(), config.getDataLogDir(), config
                .getSnapRetainCount(), config.getPurgeInterval());
        //启动
        purgeMgr.start();
        //判断是否是集群模式,如果是 -> runFromConfig
        if (args.length == 1 && config.isDistributed()) {
            //从配置启动集群
            runFromConfig(config);
        } else {
            //否则与转发给单机版的main
            LOG.warn("Either no config or no quorum defined in config, running "
                    + " in standalone mode");
            // there is only server in the quorum -- run as standalone
            ZooKeeperServerMain.main(args);
        }
    }
    //gogogo,从配置读取,并启动主服务
    public void runFromConfig(QuorumPeerConfig config)
            throws IOException, AdminServerException
    {
      try {
          //注册jmx的监控mbean
          ManagedUtil.registerLog4jMBeans();
      } catch (JMException e) {
          LOG.warn("Unable to register log4j JMX control", e);
      }

      LOG.info("Starting quorum peer");
      MetricsProvider metricsProvider;
      try {
          //指标 //TODO 还是一些指标
        metricsProvider = MetricsProviderBootstrap
                      .startMetricsProvider(config.getMetricsProviderClassName(),
                                            config.getMetricsProviderConfiguration());
      } catch (MetricsProviderLifeCycleException error) {
        throw new IOException("Cannot boot MetricsProvider " + config.getMetricsProviderClassName(),
                      error);
      }
      try {

          ServerCnxnFactory cnxnFactory = null;
          ServerCnxnFactory secureCnxnFactory = null;
          //普通的serverio处理factory
          if (config.getClientPortAddress() != null) {
              //默认NIO
              cnxnFactory = ServerCnxnFactory.createFactory();
              cnxnFactory.configure(config.getClientPortAddress(),
                      config.getMaxClientCnxns(),
                      config.getClientPortListenBacklog(), false);
          }
          //安全的的serverio处理factory
          if (config.getSecureClientPortAddress() != null) {
              secureCnxnFactory = ServerCnxnFactory.createFactory();
              secureCnxnFactory.configure(config.getSecureClientPortAddress(),
                      config.getMaxClientCnxns(),
                      config.getClientPortListenBacklog(), true);
          }
          //集群server实例
          quorumPeer = getQuorumPeer();
          quorumPeer.setRootMetricsContext(metricsProvider.getRootContext());
          //设置日志和数据目录上下文
          quorumPeer.setTxnFactory(new FileTxnSnapLog(
                      config.getDataLogDir(),
                      config.getDataDir()));
          quorumPeer.enableLocalSessions(config.areLocalSessionsEnabled());
          quorumPeer.enableLocalSessionsUpgrading(
              config.isLocalSessionsUpgradingEnabled());
          //quorumPeer.setQuorumPeers(config.getAllMembers());
          quorumPeer.setElectionType(config.getElectionAlg());
          //集群时候的serverid,cfg里配置的
          quorumPeer.setMyid(config.getServerId());
          //设置滴答时间 也就是通信时间 (心跳)
          quorumPeer.setTickTime(config.getTickTime());
          quorumPeer.setMinSessionTimeout(config.getMinSessionTimeout());
          quorumPeer.setMaxSessionTimeout(config.getMaxSessionTimeout());
          quorumPeer.setInitLimit(config.getInitLimit());
          quorumPeer.setSyncLimit(config.getSyncLimit());
          //设置观察者的连入端口
          quorumPeer.setObserverMasterPort(config.getObserverMasterPort());
          //设置配置文件名称
          quorumPeer.setConfigFileName(config.getConfigFilename());
          quorumPeer.setClientPortListenBacklog(config.getClientPortListenBacklog());
          //设置database(本地磁盘)
          quorumPeer.setZKDatabase(new ZKDatabase(quorumPeer.getTxnFactory()));
          //创建集群验证(不同步到磁盘)
          quorumPeer.setQuorumVerifier(config.getQuorumVerifier(), false);
          if (config.getLastSeenQuorumVerifier()!=null) {
              quorumPeer.setLastSeenQuorumVerifier(config.getLastSeenQuorumVerifier(), false);
          }
          //初始化zk数据库
          quorumPeer.initConfigInZKDatabase();
          quorumPeer.setCnxnFactory(cnxnFactory);
          quorumPeer.setSecureCnxnFactory(secureCnxnFactory);
          quorumPeer.setSslQuorum(config.isSslQuorum());
          quorumPeer.setUsePortUnification(config.shouldUsePortUnification());
          quorumPeer.setLearnerType(config.getPeerType());
          quorumPeer.setSyncEnabled(config.getSyncEnabled());
          quorumPeer.setQuorumListenOnAllIPs(config.getQuorumListenOnAllIPs());
          if (config.sslQuorumReloadCertFiles) {
              quorumPeer.getX509Util().enableCertFileReloading();
          }

          // sets quorum sasl authentication configurations
          quorumPeer.setQuorumSaslEnabled(config.quorumEnableSasl);
          if(quorumPeer.isQuorumSaslAuthEnabled()){
              quorumPeer.setQuorumServerSaslRequired(config.quorumServerRequireSasl);
              quorumPeer.setQuorumLearnerSaslRequired(config.quorumLearnerRequireSasl);
              quorumPeer.setQuorumServicePrincipal(config.quorumServicePrincipal);
              quorumPeer.setQuorumServerLoginContext(config.quorumServerLoginContext);
              quorumPeer.setQuorumLearnerLoginContext(config.quorumLearnerLoginContext);
          }
          //同时处理io的线程数
          quorumPeer.setQuorumCnxnThreadsSize(config.quorumCnxnThreadsSize);
          //初始化这个server
          //重新初始化一次 用于区别是否有auth验证 ???
          quorumPeer.initialize();
          
          quorumPeer.start();
          //主线程进行等待
          quorumPeer.join();
      } catch (InterruptedException e) {
          // warn, but generally this is ok
          LOG.warn("Quorum Peer interrupted", e);
      } finally {
          if (metricsProvider != null) {
              try {
                  metricsProvider.stop();
              } catch (Throwable error) {
                  LOG.warn("Error while stopping metrics", error);
              }
          }
      }
    }

    // @VisibleForTesting
    protected QuorumPeer getQuorumPeer() throws SaslException {
        return new QuorumPeer();
    }
}
