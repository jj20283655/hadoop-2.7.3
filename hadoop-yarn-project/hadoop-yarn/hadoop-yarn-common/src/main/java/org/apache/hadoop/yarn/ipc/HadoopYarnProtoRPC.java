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

package org.apache.hadoop.yarn.ipc;

import java.net.InetSocketAddress;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.security.token.SecretManager;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.yarn.factory.providers.RpcFactoryProvider;

/**
 * This uses Hadoop RPC. Uses a tunnel ProtoSpecificRpcEngine over 
 * Hadoop connection.
 * This does not give cross-language wire compatibility, since the Hadoop 
 * RPC wire format is non-standard, but it does permit use of Protocol Buffers
 *  protocol versioning features for inter-Java RPCs.
 */
@InterfaceAudience.LimitedPrivate({ "MapReduce", "YARN" })
public class HadoopYarnProtoRPC extends YarnRPC {

  private static final Log LOG = LogFactory.getLog(HadoopYarnProtoRPC.class);

  @Override
	public Object getProxy(Class protocol, InetSocketAddress addr, Configuration conf) {
		LOG.debug("Creating a HadoopYarnProtoRpc proxy for protocol " + protocol);
		// 默认的clientfactory是org.apache.hadoop.yarn.factories.impl.pb.RpcClientFactoryPBImpl
		return RpcFactoryProvider.getClientFactory(conf)
				.getClient(protocol, 1, addr, conf);
	}

  @Override
  public void stopProxy(Object proxy, Configuration conf) {
    RpcFactoryProvider.getClientFactory(conf).stopClient(proxy);
  }

 
  /*
   * 以ResourceTrackerService为例 
   *@param protocol org.apache.hadoop.yarn.server.api.ResourceTracker
   *@param instance org.apache.hadoop.yarn.server.resourcemanager.ResourceTrackerService
   *@param addr
   *@param conf
   *@param secretManager
   *@param numHandlers
   *@param portRangeConfig
   *@return
   *@author wuchang
   */
  @Override
  public Server getServer(Class protocol, Object instance,
      InetSocketAddress addr, Configuration conf,
      SecretManager<? extends TokenIdentifier> secretManager,
      int numHandlers, String portRangeConfig) {
    LOG.debug("Creating a HadoopYarnProtoRpc server for protocol " + protocol + 
        " with " + numHandlers + " handlers");
    
    //protocol ResourceTracker
    //instance ResourceTrackerService
    
    //获取默认的YARN RPC 工厂类
  //org.apache.hadoop.yarn.factories.impl.pb.RpcServerFactoryPBImpl
    return RpcFactoryProvider.getServerFactory(conf) 
    		.getServer(protocol, 
        instance, addr, conf, secretManager, numHandlers, portRangeConfig);

  }

}
