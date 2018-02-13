// Copyright 2017, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at http://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.wlsconfig;

import oracle.kubernetes.operator.domain.model.oracle.kubernetes.weblogic.domain.v1.ClusterStartup;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.logging.MessageKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Contains configuration of a WLS cluster
 * <p>
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
 */
public class WlsClusterConfig {
  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");

  private final String clusterName;
  private List<WlsServerConfig> serverConfigs = new ArrayList<>();
  private final WlsDynamicServersConfig dynamicServersConfig;

  /**
   * Constructor for a static cluster when Json result is not available
   *
   * @param clusterName Name of the WLS cluster
   */
  public WlsClusterConfig(String clusterName) {
    this.clusterName = clusterName;
    this.dynamicServersConfig = null;
  }

  /**
   * Constructor for a dynamic cluster
   * *
   * @param clusterName Name of the WLS cluster
   * @param dynamicServersConfig A WlsDynamicServersConfig object containing the dynamic servers configuration for this
   *                             cluster
   */
  public WlsClusterConfig(String clusterName, WlsDynamicServersConfig dynamicServersConfig) {
    this.clusterName = clusterName;
    this.dynamicServersConfig = dynamicServersConfig;
  }

  /**
   * Creates a WlsClusterConfig object using an "clusters" item parsed from JSON result from WLS REST call
   *
   * @param clusterConfigMap Map containing "cluster" item parsed from JSON result from WLS REST call
   * @param serverTemplates Map containing all server templates configuration read from the WLS domain
   * @param domainName Name of the WLS domain that this WLS cluster belongs to
   *
   * @return A new WlsClusterConfig object created based on the JSON result
   */
  static WlsClusterConfig create(Map<String, Object> clusterConfigMap, Map<String, WlsServerConfig> serverTemplates, String domainName) {
    String clusterName = (String) clusterConfigMap.get("name");
    WlsDynamicServersConfig dynamicServersConfig =
            WlsDynamicServersConfig.create((Map) clusterConfigMap.get("dynamicServers"), serverTemplates, clusterName, domainName);
    // set dynamicServersConfig only if the cluster contains dynamic servers, i.e., its dynamic servers configuration
    // contains non-null server template name
    if (dynamicServersConfig.getServerTemplate() == null) {
      dynamicServersConfig = null;
    }
    return new WlsClusterConfig(clusterName, dynamicServersConfig);
  }

  /**
   * Add a statically configured WLS server to this cluster
   *
   * @param wlsServerConfig A WlsServerConfig object containing the configuration of the statically configured WLS server
   *                        that belongs to this cluster
   */
  synchronized void addServerConfig(WlsServerConfig wlsServerConfig) {
    serverConfigs.add(wlsServerConfig);
  }

  /**
   * Returns the number of servers that are statically configured in this cluster
   *
   * @return The number of servers that are statically configured in this cluster
   */
  public synchronized int getClusterSize() {
    return serverConfigs.size();
  }

  /**
   * Returns the name of the cluster that this WlsClusterConfig is created for
   *
   * @return the name of the cluster that this WlsClusterConfig is created for
   */
  public String getClusterName() {
    return clusterName;
  }

  /**
   * Returns a list of server configurations for servers that belong to this cluster, which includes
   * both statically configured servers and dynamic servers
   *
   * @return A list of WlsServerConfig containing configurations of servers that belong to this cluster
   */
  public synchronized List<WlsServerConfig> getServerConfigs() {
    if (dynamicServersConfig != null) {
      List<WlsServerConfig> result = new ArrayList<>(dynamicServersConfig.getDynamicClusterSize() + serverConfigs.size());
      result.addAll(dynamicServersConfig.getServerConfigs());
      result.addAll(serverConfigs);
      return result;
    }
    return serverConfigs;
  }

  /**
   * Whether the cluster contains any statically configured servers
   * @return True if the cluster contains any statically configured servers
   */
  public synchronized boolean hasStaticServers() {
    return !serverConfigs.isEmpty();
  }

  /**
   * Whether the cluster contains any dynamic servers
   * @return True if the cluster contains any dynamic servers
   */
  public boolean hasDynamicServers() {
    return dynamicServersConfig != null;
  }

  /**
   * Returns the current size of the dynamic cluster (the number of dynamic server instances allowed
   * to be created)
   *
   * @return the current size of the dynamic cluster, or -1 if there is no dynamic servers in this cluster
   */
  public int getDynamicClusterSize() {
    return dynamicServersConfig != null? dynamicServersConfig.getDynamicClusterSize(): -1;
  }

  /**
   * Returns the maximum size of the dynamic cluster
   *
   * @return the maximum size of the dynamic cluster, or -1 if there is no dynamic servers in this cluster
   */
  public int getMaxDynamicClusterSize() {
    return dynamicServersConfig != null? dynamicServersConfig.getMaxDynamicClusterSize(): -1;
  }

  /**
   * Validate the clusterStartup configured should be consistent with this configured WLS cluster. The method
   * also logs warning if inconsistent WLS configurations are found.
   * <p>
   * In the future this method may also attempt to fix the configuration inconsistencies by updating the ClusterStartup.
   * It is the responsibility of the caller to persist the changes to ClusterStartup to kubernetes.
   *
   * @param clusterStartup The ClusterStartup to be validated against the WLS configuration
   * @return true if the DomainSpec has been updated, false otherwise
   */
  public boolean validateClusterStartup(ClusterStartup clusterStartup) {
    LOGGER.entering();

    boolean modified = false;

    // log warning if no servers are configured in the cluster
    if (getClusterSize() == 0 && !hasDynamicServers()) {
      LOGGER.warning(MessageKeys.NO_WLS_SERVER_IN_CLUSTER, clusterName);
    }

    // Warns if replicas is larger than the number of servers configured in the cluster
    validateReplicas(clusterStartup.getReplicas(), "clusterStartup");

    LOGGER.exiting(modified);

    return modified;
  }


  /**
   * Validate the configured replicas value in the kubernetes weblogic domain spec against the
   * configured size of this cluster. Log warning if any inconsistencies are found.
   *
   * @param replicas The configured replicas value for this cluster in the kubernetes weblogic domain spec
   *                 for this cluster
   * @param source The name of the section in the domain spec where the replicas is specified,
   *               for logging purposes
   */
  public void validateReplicas(Integer replicas, String source) {
    if (replicas != null && replicas > getClusterSize() && !hasDynamicServers()) {
      LOGGER.warning(MessageKeys.REPLICA_MORE_THAN_WLS_SERVERS, source, clusterName, replicas, getClusterSize());
    }
  }

  /**
   * Return the list of configuration attributes to be retrieved from the REST search request to the
   * WLS admin server. The value would be used for constructing the REST POST request.
   *
   * @return The list of configuration attributes to be retrieved from the REST search request
   * to the WLS admin server. The value would be used for constructing the REST POST request.
   */
  static String getSearchPayload() {
    return  "   fields: [ " + getSearchFields() + " ], " +
            "   links: [], " +
            "   children: { " +
            "      dynamicServers: { " +
            "      fields: [ " + WlsDynamicServersConfig.getSearchFields() + " ], " +
            "      links: [] " +
            "        }" +
            "    } ";
  }

  /**
   * Return the fields from cluster WLS configuration that should be retrieved from the WLS REST
   * request.
   *
   * @return A string containing cluster configuration fields that should be retrieved from the WLS REST
   *         request, in a format that can be used in the REST request payload
   */
  private static String getSearchFields() {
    return "'name' ";
  }

  /**
   * Return the URL path of REST request for updating dynamic cluster size
   *
   * @return The REST URL path for updating cluster size of dynamic servers for this cluster
   */
  public String getUpdateDynamicClusterSizeUrl() {
    return "/management/weblogic/latest/edit/clusters/" + clusterName + "/dynamicServers";
  }

  /**
   * Return the payload used in the REST request for updating the dynamic cluster size. It will
   * be used to update the cluster size and if necessary, the max cluster size of the dynamic servers
   * of this cluster.
   *
   * @param clusterSize Desired dynamic cluster size
   * @return A string containing the payload to be used in the REST request for updating the dynamic
   * cluster size to the specified value.
   */
  public String getUpdateDynamicClusterSizePayload(final int clusterSize) {
    return "{ dynamicClusterSize: " + clusterSize + ", " +
            " maxDynamicClusterSize: " + (clusterSize > getMaxDynamicClusterSize()? clusterSize: getMaxDynamicClusterSize()) +
            " }";
  }

  @Override
  public String toString() {
    return "WlsClusterConfig{" +
            "clusterName='" + clusterName + '\'' +
            ", serverConfigs=" + serverConfigs +
            ", dynamicServersConfig=" + dynamicServersConfig +
            '}';
  }

}
