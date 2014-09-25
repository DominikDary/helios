/*
 * Copyright (c) 2014 Spotify AB.
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
 */

package com.spotify.helios.servicescommon.coordination;

import com.fasterxml.jackson.databind.JavaType;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;
import org.apache.curator.framework.api.transaction.CuratorTransactionResult;
import org.apache.curator.framework.listen.Listenable;
import org.apache.curator.framework.recipes.nodes.PersistentEphemeralNode;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.utils.EnsurePath;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKUtil;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Throwables.propagate;
import static com.google.common.base.Throwables.propagateIfInstanceOf;
import static com.google.common.collect.Lists.reverse;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;

public class DefaultZooKeeperClient implements ZooKeeperClient {

  private static final Logger log = LoggerFactory.getLogger(DefaultZooKeeperClient.class);

  private final CuratorFramework client;
  private final String clusterId;
  private final AtomicBoolean clusterIdExists;
  private final Watcher watcher;
  private final ConnectionStateListener connectionStateListener;

  public DefaultZooKeeperClient(final CuratorFramework client) {
    this(client, null);
  }

  public DefaultZooKeeperClient(final CuratorFramework client, final String clusterId) {
    this.client = client;
    this.clusterId = clusterId;

    if (clusterId == null) {
      this.clusterIdExists = null;
      this.watcher = null;
      this.connectionStateListener = null;
      return;
    }

    this.clusterIdExists = new AtomicBoolean(false);

    this.watcher = new Watcher() {
      @Override
      public void process(WatchedEvent event) {
        log.error("*** WATCH EVENT - " + event.toString());
        checkClusterIdExists(clusterId);
      }
    };

    connectionStateListener = new ConnectionStateListener() {
      @Override
      public void stateChanged(CuratorFramework client, ConnectionState newState) {
        if (newState == ConnectionState.RECONNECTED) {
          checkClusterIdExists(clusterId);
        }
      }
    };
  }

  @Override
  public CuratorFramework getCuratorFramework() {
    return client;
  }

  @Override
  /** {@inheritDoc} */
  public void ensurePath(final String path) throws KeeperException {
    assertClusterIdFlagTrue();
    ensurePath(path, false);
  }

  @Override
  /** {@inheritDoc} */
  public void ensurePath(final String path, final boolean excludingLast) throws KeeperException {
    assertClusterIdFlagTrue();
    EnsurePath ensurePath = client.newNamespaceAwareEnsurePath(path);
    if (excludingLast) {
      ensurePath = ensurePath.excludingLast();
    }
    try {
      ensurePath.ensure(client.getZookeeperClient());
    } catch (Exception e) {
      propagateIfInstanceOf(e, KeeperException.class);
      throw propagate(e);
    }
  }

  @Override
  public byte[] getData(final String path) throws KeeperException {
    assertClusterIdFlagTrue();
    try {
      return client.getData().forPath(path);
    } catch (Exception e) {
      propagateIfInstanceOf(e, KeeperException.class);
      throw propagate(e);
    }
  }

  @Override
  public Node getNode(final String path) throws KeeperException {
    assertClusterIdFlagTrue();
    final Stat stat = new Stat();
    try {
      byte[] bytes = client.getData().storingStatIn(stat).forPath(path);
      return new Node(path, bytes, stat);
    } catch (Exception e) {
      propagateIfInstanceOf(e, KeeperException.class);
      throw propagate(e);
    }
  }

  @Override
  public Stat exists(final String path) throws KeeperException {
    assertClusterIdFlagTrue();
    try {
      return client.checkExists().forPath(path);
    } catch (Exception e) {
      propagateIfInstanceOf(e, KeeperException.class);
      throw propagate(e);
    }
  }

  @Override
  public void start() {
    client.start();
    client.getConnectionStateListenable().addListener(connectionStateListener);
    checkClusterIdExists(clusterId);
  }

  @Override
  public void close() {
    client.getConnectionStateListenable().removeListener(connectionStateListener);
    client.close();
  }

  @Override
  public PersistentEphemeralNode persistentEphemeralNode(final String path,
                                                         final PersistentEphemeralNode.Mode mode,
                                                         final byte[] data) {
    assertClusterIdFlagTrue();
    return new PersistentEphemeralNode(client, mode, path, data);
  }

  @Override
  public Listenable<ConnectionStateListener> getConnectionStateListenable() {
    return client.getConnectionStateListenable();
  }

  @Override
  public ZooKeeper.States getState() throws KeeperException {
    assertClusterIdFlagTrue();
    try {
      return client.getZookeeperClient().getZooKeeper().getState();
    } catch (Exception e) {
      propagateIfInstanceOf(e, KeeperException.class);
      throw propagate(e);
    }
  }

  @Override
  public List<String> getChildren(final String path) throws KeeperException {
    assertClusterIdFlagTrue();
    try {
      return client.getChildren().forPath(path);
    } catch (Exception e) {
      propagateIfInstanceOf(e, KeeperException.class);
      throw propagate(e);
    }
  }

  @Override
  public void deleteRecursive(final String path) throws KeeperException {
    assertClusterIdFlagTrue();
    try {
      final List<String> nodes = listRecursive(path);
      if (nodes.isEmpty()) {
        return;
      }
      final CuratorTransactionFinal t = client.inTransaction().check().forPath(path).and();
      for (final String node : reverse(nodes))  {
        t.delete().forPath(node).and();
      }
      t.commit();
    } catch (Exception e) {
      propagateIfInstanceOf(e, KeeperException.class);
      throw propagate(e);
    }
  }

  @Override
  public List<String> listRecursive(final String path) throws KeeperException {
    assertClusterIdFlagTrue();
    try {
      return ZKUtil.listSubTreeBFS(client.getZookeeperClient().getZooKeeper(), path);
    } catch (Exception e) {
      propagateIfInstanceOf(e, KeeperException.class);
      throw propagate(e);
    }
  }

  @Override
  public void delete(final String path) throws KeeperException {
    assertClusterIdFlagTrue();
    try {
      client.delete().forPath(path);
    } catch (Exception e) {
      propagateIfInstanceOf(e, KeeperException.class);
      throw propagate(e);
    }
  }

  @Override
  public void delete(final String path, final int version) throws KeeperException {
    assertClusterIdFlagTrue();
    try {
      client.getZookeeperClient().getZooKeeper().delete(path, version);
    } catch (Exception e) {
      propagateIfInstanceOf(e, KeeperException.class);
      throw propagate(e);
    }
  }

  @Override
  public void createAndSetData(final String path, final byte[] data) throws KeeperException {
    assertClusterIdFlagTrue();
    try {
      client.create().forPath(path, data);
    } catch (Exception e) {
      propagateIfInstanceOf(e, KeeperException.class);
      throw propagate(e);
    }
  }

  @Override
  public void create(final String path) throws KeeperException {
    assertClusterIdFlagTrue();
    try {
      client.create().forPath(path);
    } catch (Exception e) {
      propagateIfInstanceOf(e, KeeperException.class);
      throw propagate(e);
    }
  }

  @Override
  public void createWithMode(final String path, final CreateMode mode) throws KeeperException {
    assertClusterIdFlagTrue();
    try {
      client.create().withMode(mode).forPath(path);
    } catch (Exception e) {
      propagateIfInstanceOf(e, KeeperException.class);
      throw propagate(e);
    }
  }

  @Override
  public void setData(final String path, final byte[] data) throws KeeperException {
    assertClusterIdFlagTrue();
    try {
      client.setData().forPath(path, data);
    } catch (Exception e) {
      propagateIfInstanceOf(e, KeeperException.class);
      throw propagate(e);
    }
  }

  @Override
  public Stat stat(final String path) throws KeeperException {
    assertClusterIdFlagTrue();
    try {
      return client.checkExists().forPath(path);
    } catch (Exception e) {
      propagateIfInstanceOf(e, KeeperException.class);
      throw propagate(e);
    }
  }

  @Override
  public <T> PersistentPathChildrenCache<T> pathChildrenCache(final String path,
                                                              final Path snapshotFile,
                                                              final JavaType valueType)
      throws IOException, InterruptedException {
    return new PersistentPathChildrenCache<T>(client, path, clusterId, snapshotFile, valueType);
  }

  @Override
  public Collection<CuratorTransactionResult> transaction(final List<ZooKeeperOperation> operations)
      throws KeeperException {
    assertClusterIdFlagTrue();
    log.debug("transaction: {}", operations);

    if (operations.isEmpty()) {
      return emptyList();
    }

    // Assemble transaction
    final CuratorTransactionFinal transaction = (CuratorTransactionFinal) client.inTransaction();
    for (final ZooKeeperOperation operation : operations) {
      try {
        operation.register(transaction);
      } catch (final Exception e) {
        throw propagate(e);
      }
    }

    // Commit
    try {
      return transaction.commit();
    } catch (Exception e) {
      propagateIfInstanceOf(e, KeeperException.class);
      throw propagate(e);
    }
  }

  @Override
  public Collection<CuratorTransactionResult> transaction(final ZooKeeperOperation... operations)
      throws KeeperException {
    return transaction(asList(operations));
  }

  private void assertClusterIdFlagTrue() {
    if (clusterId != null && !clusterIdExists.get()) {
      throw new IllegalStateException("ZooKeeper cluster ID does not exist");
    }
  }

  private void checkClusterIdExists(final String id) {
    try {
      final Stat stat = client.checkExists().usingWatcher(watcher).forPath(Paths.configId(id));
      log.error("*** STAT {}", stat);
      clusterIdExists.set(stat != null);
    } catch (Exception e) {
      clusterIdExists.set(false);
      log.error("Exception while checking ZooKeeper cluster ID {}", clusterId, e);
    }
  }
}
