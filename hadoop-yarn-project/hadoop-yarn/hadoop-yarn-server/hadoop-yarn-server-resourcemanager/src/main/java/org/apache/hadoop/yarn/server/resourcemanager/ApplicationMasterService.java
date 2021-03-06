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

package org.apache.hadoop.yarn.server.resourcemanager;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.security.SaslRpcServer;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.PolicyProvider;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.ApplicationMasterProtocol;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateRequest;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationMasterRequest;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationMasterResponse;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterRequest;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.NMToken;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.PreemptionContainer;
import org.apache.hadoop.yarn.api.records.PreemptionContract;
import org.apache.hadoop.yarn.api.records.PreemptionMessage;
import org.apache.hadoop.yarn.api.records.PreemptionResourceRequest;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceBlacklistRequest;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.api.records.StrictPreemptionContract;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.ApplicationAttemptNotFoundException;
import org.apache.hadoop.yarn.exceptions.ApplicationMasterNotRegisteredException;
import org.apache.hadoop.yarn.exceptions.InvalidApplicationMasterRequestException;
import org.apache.hadoop.yarn.exceptions.InvalidContainerReleaseException;
import org.apache.hadoop.yarn.exceptions.InvalidResourceBlacklistRequestException;
import org.apache.hadoop.yarn.exceptions.InvalidResourceRequestException;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.ipc.RPCUtil;
import org.apache.hadoop.yarn.ipc.YarnRPC;
import org.apache.hadoop.yarn.security.AMRMTokenIdentifier;
import org.apache.hadoop.yarn.server.resourcemanager.RMAuditLogger.AuditConstants;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.AMLivelinessMonitor;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.event.RMAppAttemptRegistrationEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.event.RMAppAttemptStatusupdateEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.event.RMAppAttemptUnregistrationEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNode;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.AbstractYarnScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.Allocation;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerNodeReport;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.YarnScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.security.authorize.RMPolicyProvider;
import org.apache.hadoop.yarn.server.security.MasterKeyData;
import org.apache.hadoop.yarn.server.utils.BuilderUtils;

import com.google.common.annotations.VisibleForTesting;

@SuppressWarnings("unchecked")
@Private
public class ApplicationMasterService extends AbstractService implements
    ApplicationMasterProtocol {
  private static final Log LOG = LogFactory.getLog(ApplicationMasterService.class);
  private final AMLivelinessMonitor amLivelinessMonitor;
  private YarnScheduler rScheduler;
  private InetSocketAddress bindAddress;
  private Server server;
  private final RecordFactory recordFactory =
      RecordFactoryProvider.getRecordFactory(null);
  //记录了所有Application的attemp信息，这个信息被RM管理和掌控
  private final ConcurrentMap<ApplicationAttemptId, AllocateResponseLock> responseMap =
      new ConcurrentHashMap<ApplicationAttemptId, AllocateResponseLock>();
  private final RMContext rmContext;

  /**
   * 
   * @param rmContext 实现类是
   * @param scheduler 具体的实现类是FairScheduler或者CapacityScheduler
   */
  public ApplicationMasterService(RMContext rmContext, YarnScheduler scheduler) {
    super(ApplicationMasterService.class.getName());
    this.amLivelinessMonitor = rmContext.getAMLivelinessMonitor();
    this.rScheduler = scheduler;
    this.rmContext = rmContext;
  }

  @Override
  protected void serviceStart() throws Exception {
    Configuration conf = getConfig();
    YarnRPC rpc = YarnRPC.create(conf);

    InetSocketAddress masterServiceAddress = conf.getSocketAddr(
        YarnConfiguration.RM_BIND_HOST,
        YarnConfiguration.RM_SCHEDULER_ADDRESS,
        YarnConfiguration.DEFAULT_RM_SCHEDULER_ADDRESS,
        YarnConfiguration.DEFAULT_RM_SCHEDULER_PORT);

    Configuration serverConf = conf;
    // If the auth is not-simple, enforce it to be token-based.
    serverConf = new Configuration(conf);
    serverConf.set(
        CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHENTICATION,
        SaslRpcServer.AuthMethod.TOKEN.toString());
    this.server =
      rpc.getServer(ApplicationMasterProtocol.class, this, masterServiceAddress,
          serverConf, this.rmContext.getAMRMTokenSecretManager(),
          serverConf.getInt(YarnConfiguration.RM_SCHEDULER_CLIENT_THREAD_COUNT, 
              YarnConfiguration.DEFAULT_RM_SCHEDULER_CLIENT_THREAD_COUNT));
    
    // Enable service authorization?
    if (conf.getBoolean(
        CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHORIZATION, 
        false)) {
      InputStream inputStream =
          this.rmContext.getConfigurationProvider()
              .getConfigurationInputStream(conf,
                  YarnConfiguration.HADOOP_POLICY_CONFIGURATION_FILE);
      if (inputStream != null) {
        conf.addResource(inputStream);
      }
      refreshServiceAcls(conf, RMPolicyProvider.getInstance());
    }
    
    this.server.start();
    this.bindAddress =
        conf.updateConnectAddr(YarnConfiguration.RM_BIND_HOST,
                               YarnConfiguration.RM_SCHEDULER_ADDRESS,
                               YarnConfiguration.DEFAULT_RM_SCHEDULER_ADDRESS,
                               server.getListenerAddress());
    super.serviceStart();
  }

  @Private
  public InetSocketAddress getBindAddress() {
    return this.bindAddress;
  }

  // Obtain the needed AMRMTokenIdentifier from the remote-UGI. RPC layer
  // currently sets only the required id, but iterate through anyways just to be
  // sure.
  private AMRMTokenIdentifier selectAMRMTokenIdentifier(
      UserGroupInformation remoteUgi) throws IOException {
    AMRMTokenIdentifier result = null;
    Set<TokenIdentifier> tokenIds = remoteUgi.getTokenIdentifiers();
    for (TokenIdentifier tokenId : tokenIds) {
      if (tokenId instanceof AMRMTokenIdentifier) {
        result = (AMRMTokenIdentifier) tokenId;
        break;
      }
    }

    return result;
  }

  private AMRMTokenIdentifier authorizeRequest()
      throws YarnException {

    UserGroupInformation remoteUgi;
    try {
      remoteUgi = UserGroupInformation.getCurrentUser();
    } catch (IOException e) {
      String msg =
          "Cannot obtain the user-name for authorizing ApplicationMaster. "
              + "Got exception: " + StringUtils.stringifyException(e);
      LOG.warn(msg);
      throw RPCUtil.getRemoteException(msg);
    }

    boolean tokenFound = false;
    String message = "";
    AMRMTokenIdentifier appTokenIdentifier = null;
    try {
      appTokenIdentifier = selectAMRMTokenIdentifier(remoteUgi);
      if (appTokenIdentifier == null) {
        tokenFound = false;
        message = "No AMRMToken found for user " + remoteUgi.getUserName();
      } else {
        tokenFound = true;
      }
    } catch (IOException e) {
      tokenFound = false;
      message =
          "Got exception while looking for AMRMToken for user "
              + remoteUgi.getUserName();
    }

    if (!tokenFound) {
      LOG.warn(message);
      throw RPCUtil.getRemoteException(message);
    }

    return appTokenIdentifier;
  }

  @Override
  public RegisterApplicationMasterResponse registerApplicationMaster(
      RegisterApplicationMasterRequest request) throws YarnException,
      IOException {

    AMRMTokenIdentifier amrmTokenIdentifier = authorizeRequest();
    ApplicationAttemptId applicationAttemptId =
        amrmTokenIdentifier.getApplicationAttemptId();

    ApplicationId appID = applicationAttemptId.getApplicationId();
    AllocateResponseLock lock = responseMap.get(applicationAttemptId);
    if (lock == null) {
      RMAuditLogger.logFailure(this.rmContext.getRMApps().get(appID).getUser(),
          AuditConstants.REGISTER_AM, "Application doesn't exist in cache "
              + applicationAttemptId, "ApplicationMasterService",
          "Error in registering application master", appID,
          applicationAttemptId);
      throwApplicationDoesNotExistInCacheException(applicationAttemptId);
    }

    // Allow only one thread in AM to do registerApp at a time.
    synchronized (lock) {
      AllocateResponse lastResponse = lock.getAllocateResponse();
      if (hasApplicationMasterRegistered(applicationAttemptId)) {
        String message =
            "Application Master is already registered : "
                + appID;
        LOG.warn(message);
        RMAuditLogger.logFailure(
          this.rmContext.getRMApps()
            .get(appID).getUser(),
          AuditConstants.REGISTER_AM, "", "ApplicationMasterService", message,
          appID, applicationAttemptId);
        throw new InvalidApplicationMasterRequestException(message);
      }
      
      this.amLivelinessMonitor.receivedPing(applicationAttemptId);
      RMApp app = this.rmContext.getRMApps().get(appID);
      
      // Setting the response id to 0 to identify if the
      // application master is register for the respective attemptid
      lastResponse.setResponseId(0);
      lock.setAllocateResponse(lastResponse);
      LOG.info("AM registration " + applicationAttemptId);
      this.rmContext
        .getDispatcher()
        .getEventHandler()
        .handle(
          new RMAppAttemptRegistrationEvent(applicationAttemptId, request
            .getHost(), request.getRpcPort(), request.getTrackingUrl()));
      RMAuditLogger.logSuccess(app.getUser(), AuditConstants.REGISTER_AM,
        "ApplicationMasterService", appID, applicationAttemptId);

      // Pick up min/max resource from scheduler...
      RegisterApplicationMasterResponse response = recordFactory
          .newRecordInstance(RegisterApplicationMasterResponse.class);
      response.setMaximumResourceCapability(rScheduler
          .getMaximumResourceCapability(app.getQueue()));
      response.setApplicationACLs(app.getRMAppAttempt(applicationAttemptId)
          .getSubmissionContext().getAMContainerSpec().getApplicationACLs());
      response.setQueue(app.getQueue());
      if (UserGroupInformation.isSecurityEnabled()) {
        LOG.info("Setting client token master key");
        response.setClientToAMTokenMasterKey(java.nio.ByteBuffer.wrap(rmContext
            .getClientToAMTokenSecretManager()
            .getMasterKey(applicationAttemptId).getEncoded()));        
      }

      // For work-preserving AM restart, retrieve previous attempts' containers
      // and corresponding NM tokens.
      if (app.getApplicationSubmissionContext()
          .getKeepContainersAcrossApplicationAttempts()) {//如果允许在一个ApplicationContainer失效并重启以后使用以前的正在运行的container
        List<Container> transferredContainers = ((AbstractYarnScheduler) rScheduler)
            .getTransferredContainers(applicationAttemptId);
        if (!transferredContainers.isEmpty()) {
          response.setContainersFromPreviousAttempts(transferredContainers);
          List<NMToken> nmTokens = new ArrayList<NMToken>();
          for (Container container : transferredContainers) {
            try {
              NMToken token = rmContext.getNMTokenSecretManager()
                  .createAndGetNMToken(app.getUser(), applicationAttemptId,
                      container);
              if (null != token) {
                nmTokens.add(token);
              }
            } catch (IllegalArgumentException e) {
              // if it's a DNS issue, throw UnknowHostException directly and
              // that
              // will be automatically retried by RMProxy in RPC layer.
              if (e.getCause() instanceof UnknownHostException) {
                throw (UnknownHostException) e.getCause();
              }
            }
          }
          response.setNMTokensFromPreviousAttempts(nmTokens);
          LOG.info("Application " + appID + " retrieved "
              + transferredContainers.size() + " containers from previous"
              + " attempts and " + nmTokens.size() + " NM tokens.");
        }
      }

      response.setSchedulerResourceTypes(rScheduler
        .getSchedulingResourceTypes());

      return response;
    }
  }

  @Override
  public FinishApplicationMasterResponse finishApplicationMaster(
      FinishApplicationMasterRequest request) throws YarnException,
      IOException {

    ApplicationAttemptId applicationAttemptId =
        authorizeRequest().getApplicationAttemptId();
    ApplicationId appId = applicationAttemptId.getApplicationId();

    RMApp rmApp =
        rmContext.getRMApps().get(applicationAttemptId.getApplicationId());
    // checking whether the app exits in RMStateStore at first not to throw
    // ApplicationDoesNotExistInCacheException before and after
    // RM work-preserving restart.
    if (rmApp.isAppFinalStateStored()) {
      LOG.info(rmApp.getApplicationId() + " unregistered successfully. ");
      return FinishApplicationMasterResponse.newInstance(true);
    }

    AllocateResponseLock lock = responseMap.get(applicationAttemptId);
    if (lock == null) {
      throwApplicationDoesNotExistInCacheException(applicationAttemptId);
    }

    // Allow only one thread in AM to do finishApp at a time.
    synchronized (lock) {
      if (!hasApplicationMasterRegistered(applicationAttemptId)) {
        String message =
            "Application Master is trying to unregister before registering for: "
                + appId;
        LOG.error(message);
        RMAuditLogger.logFailure(
            this.rmContext.getRMApps()
                .get(appId).getUser(),
            AuditConstants.UNREGISTER_AM, "", "ApplicationMasterService",
            message, appId,
            applicationAttemptId);
        throw new ApplicationMasterNotRegisteredException(message);
      }

      this.amLivelinessMonitor.receivedPing(applicationAttemptId);

      rmContext.getDispatcher().getEventHandler().handle(
          new RMAppAttemptUnregistrationEvent(applicationAttemptId, request
              .getTrackingUrl(), request.getFinalApplicationStatus(), request
              .getDiagnostics()));

      // For UnmanagedAMs, return true so they don't retry
      return FinishApplicationMasterResponse.newInstance(
          rmApp.getApplicationSubmissionContext().getUnmanagedAM());
    }
  }

  private void throwApplicationDoesNotExistInCacheException(
      ApplicationAttemptId appAttemptId)
      throws InvalidApplicationMasterRequestException {
    String message = "Application doesn't exist in cache "
        + appAttemptId;
    LOG.error(message);
    throw new InvalidApplicationMasterRequestException(message);
  }
  
  /**
   * @param appAttemptId
   * @return true if application is registered for the respective attemptid
   * 判断这个applicationMaster是否注册过。如果注册，那么lastResponse这个map中应该保存了
   * 这个AM的id作为key，同时，responseId应该是一个不小于0的值，因为AM注册的时候设置id为0，
   * 以后每次交互都把id递增1
   */
  public boolean hasApplicationMasterRegistered(
      ApplicationAttemptId appAttemptId) {
    boolean hasApplicationMasterRegistered = false;
    AllocateResponseLock lastResponse = responseMap.get(appAttemptId);
    if (lastResponse != null) {
      synchronized (lastResponse) {
        if (lastResponse.getAllocateResponse() != null
            && lastResponse.getAllocateResponse().getResponseId() >= 0) {
          hasApplicationMasterRegistered = true;
        }
      }
    }
    return hasApplicationMasterRegistered;
  }

  
  public AllocateResponse allocate(AllocateRequest request)
      throws YarnException, IOException {

    AMRMTokenIdentifier amrmTokenIdentifier = authorizeRequest();

    ApplicationAttemptId appAttemptId =
        amrmTokenIdentifier.getApplicationAttemptId();
    ApplicationId applicationId = appAttemptId.getApplicationId();

    //每次方法调用都是一次心跳信息，因此记录此次心跳信息
    this.amLivelinessMonitor.receivedPing(appAttemptId);

    /* check if its in cache */
    //验证RM端是否已经有了ApplationMaster进程的attemptid信息
    //正常情况下,ApplicationMaster对应的进程的attemp在启动的时候应该注册给AMS，即记录在responseMap中
    AllocateResponseLock lock = responseMap.get(appAttemptId);
    if (lock == null) {
      String message =
          "Application attempt " + appAttemptId
              + " doesn't exist in ApplicationMasterService cache.";
      LOG.error(message);
      throw new ApplicationAttemptNotFoundException(message);
    }
    synchronized (lock) {
      //ApplicationMaster每次与AMS交互，都会生成并记录一个AllocateResponse，AllocateResponse
      //中记录的交互Id每次交互都会递增。从registerAppAtempt()中设置为-1，registerApplicationMaster()
      //设置为0， 以后开始每次交互均递增
      AllocateResponse lastResponse = lock.getAllocateResponse();
      //校验AM是否注册过
      if (!hasApplicationMasterRegistered(appAttemptId)) {
        String message =
            "AM is not registered for known application attempt: " + appAttemptId
                + " or RM had restarted after AM registered . AM should re-register.";
        LOG.info(message);
        RMAuditLogger.logFailure(
          this.rmContext.getRMApps().get(appAttemptId.getApplicationId())
            .getUser(), AuditConstants.AM_ALLOCATE, "",
          "ApplicationMasterService", message, applicationId, appAttemptId);
        throw new ApplicationMasterNotRegisteredException(message);
      }

      //请求中序列号为上次请求的序列号，说明是一次重复请求，则直接返回上次的response
      if ((request.getResponseId() + 1) == lastResponse.getResponseId()) {
        /* old heartbeat */
        return lastResponse;
      } else if (request.getResponseId() + 1 < lastResponse.getResponseId()) {
    	  //request里面的id是更早以前的，直接判定非法
        String message =
            "Invalid responseId in AllocateRequest from application attempt: "
                + appAttemptId + ", expect responseId to be "
                + (lastResponse.getResponseId() + 1);
        throw new InvalidApplicationMasterRequestException(message);
      }

      //过滤非法的进度信息，进度信息用一个浮点数表示，代表进程执行的百分比
      //filter illegal progress values
      float filteredProgress = request.getProgress();
      if (Float.isNaN(filteredProgress) || filteredProgress == Float.NEGATIVE_INFINITY
        || filteredProgress < 0) {
         request.setProgress(0);
      } else if (filteredProgress > 1 || filteredProgress == Float.POSITIVE_INFINITY) {
        request.setProgress(1);
      }

      // Send the status update to the appAttempt.
      //将ApplicationMaster返回到关于进度的信息，更新到ReSourceManager所维护的appAttempt中去，
      //使得这两部分信息保持一致,   this.rmContext.getDispatcher()是AsyncDispatcher，得到的
      //eventHandler是@code ApplicationAttemptEventDispatcher
      this.rmContext.getDispatcher().getEventHandler().handle(
          new RMAppAttemptStatusupdateEvent(appAttemptId, request
              .getProgress()));

      //新的资源请求
      List<ResourceRequest> ask = request.getAskList();
      //NodeManager已经释放的container信息
      List<ContainerId> release = request.getReleaseList();
      //黑名单信息，不希望自己的container分配到这些机器上
      ResourceBlacklistRequest blacklistRequest =
          request.getResourceBlacklistRequest();
      //添加到黑名单中的资源list
      List<String> blacklistAdditions =
          (blacklistRequest != null) ?
              blacklistRequest.getBlacklistAdditions() : Collections.EMPTY_LIST;
      //应该从黑名单中移除的资源名称的list
      List<String> blacklistRemovals =
          (blacklistRequest != null) ?
              blacklistRequest.getBlacklistRemovals() : Collections.EMPTY_LIST;
      //ResourceManager维护的这个application的信息,运行时，这个app是一个RMAppImpl
      RMApp app =
          this.rmContext.getRMApps().get(applicationId);
      
      // set label expression for Resource Requests if resourceName=ANY 
      ApplicationSubmissionContext asc = app.getApplicationSubmissionContext();
      for (ResourceRequest req : ask) {
    	 //ResourceRequest.ANY代表这个资源分派请求对机器不挑剔，集群中任何机器都行
        if (null == req.getNodeLabelExpression()
            && ResourceRequest.ANY.equals(req.getResourceName())) {
          //如果这个资源请求不挑机器，并且没有设置nodeLabel, 那么就将nodeLabel设置为
         //客户端提交应用时候指定的nodelabel，当然，有可能客户端提交应用的时候没有指定nodeLabel
          req.setNodeLabelExpression(asc.getNodeLabelExpression());
        }
      }
              
      //完整性检查，包括规范化NodeLabel , 同时对资源合法性进行校验
      try {
        RMServerUtils.normalizeAndValidateRequests(ask,
            rScheduler.getMaximumResourceCapability(), app.getQueue(),
            rScheduler, rmContext);
      } catch (InvalidResourceRequestException e) {
        LOG.warn("Invalid resource ask by application " + appAttemptId, e);
        throw e;
      }
      
      try {
    	  //对黑名单资源进行检查
        RMServerUtils.validateBlacklistRequest(blacklistRequest);
      }  catch (InvalidResourceBlacklistRequestException e) {
        LOG.warn("Invalid blacklist request by application " + appAttemptId, e);
        throw e;
      }

      // In the case of work-preserving AM restart, it's possible for the
      // AM to release containers from the earlier attempt.
      //在work-preserving 关闭的情况下，不应该发生申请释放的container的applicationAttemptId
      //与当前AM的attemptId不一致的 情况，如果发生，则抛出异常
      if (!app.getApplicationSubmissionContext()
        .getKeepContainersAcrossApplicationAttempts()) {
        try {
          //确认释放请求中所有的container都是当前这个application的id
          //如果真的发生了AM restart并且work-preserving AM restart打开，那么这些container中包含的
        	//getApplicationAttemptId应该与重启以后的ApplicationAttemptId不同，这时候这个
          RMServerUtils.validateContainerReleaseRequest(release, appAttemptId);
        } catch (InvalidContainerReleaseException e) {
          LOG.warn("Invalid container release by application " + appAttemptId, e);
          throw e;
        }
      }

      // Send new requests to appAttempt.
      //如果我们使用的是fairScheduler,则调用的是FairScheduler.allocate()
      Allocation allocation =
          this.rScheduler.allocate(appAttemptId, ask, release, 
              blacklistAdditions, blacklistRemovals);

      if (!blacklistAdditions.isEmpty() || !blacklistRemovals.isEmpty()) {
        LOG.info("blacklist are updated in Scheduler." +
            "blacklistAdditions: " + blacklistAdditions + ", " +
            "blacklistRemovals: " + blacklistRemovals);
      }
      RMAppAttempt appAttempt = app.getRMAppAttempt(appAttemptId);
      AllocateResponse allocateResponse =
          recordFactory.newRecordInstance(AllocateResponse.class);
      if (!allocation.getContainers().isEmpty()) {
        allocateResponse.setNMTokens(allocation.getNMTokens());
      }

      // update the response with the deltas of node status changes
      //设置response中所有节点的信息
      List<RMNode> updatedNodes = new ArrayList<RMNode>();
      if(app.pullRMNodeUpdates(updatedNodes) > 0) {//将节点信息放入到updatedNodes中
        List<NodeReport> updatedNodeReports = new ArrayList<NodeReport>();
        for(RMNode rmNode: updatedNodes) {
          SchedulerNodeReport schedulerNodeReport =  
              rScheduler.getNodeReport(rmNode.getNodeID());
          Resource used = BuilderUtils.newResource(0, 0);
          int numContainers = 0;
          if (schedulerNodeReport != null) {
            used = schedulerNodeReport.getUsedResource();
            numContainers = schedulerNodeReport.getNumContainers();
          }
          NodeId nodeId = rmNode.getNodeID();
          NodeReport report =
              BuilderUtils.newNodeReport(nodeId, rmNode.getState(),
                  rmNode.getHttpAddress(), rmNode.getRackName(), used,
                  rmNode.getTotalCapability(), numContainers,
                  rmNode.getHealthReport(), rmNode.getLastHealthReportTime(),
                  rmNode.getNodeLabels());

          updatedNodeReports.add(report);
        }
        allocateResponse.setUpdatedNodes(updatedNodeReports);
      }

      //设置已经为这个application分配的container信息到response中
      allocateResponse.setAllocatedContainers(allocation.getContainers());
      //设置已经完成的container的状态信息到response中
      allocateResponse.setCompletedContainersStatuses(appAttempt
          .pullJustFinishedContainers());
      //responseID自增1，放到response中
      allocateResponse.setResponseId(lastResponse.getResponseId() + 1);
      
      //设置集群中可用的资源信息到response中
      allocateResponse.setAvailableResources(allocation.getResourceLimit());

      //设置集群中可用节点的数目信息到response中
      allocateResponse.setNumClusterNodes(this.rScheduler.getNumClusterNodes());

      // add preemption to the allocateResponse message (if any)
      //设置抢占信息到response中
      allocateResponse
          .setPreemptionMessage(generatePreemptionMessage(allocation));

      // update AMRMToken if the token is rolled-up
      MasterKeyData nextMasterKey =
          this.rmContext.getAMRMTokenSecretManager().getNextMasterKeyData();

      if (nextMasterKey != null
          && nextMasterKey.getMasterKey().getKeyId() != amrmTokenIdentifier
            .getKeyId()) {
        RMAppAttemptImpl appAttemptImpl = (RMAppAttemptImpl)appAttempt;
        Token<AMRMTokenIdentifier> amrmToken = appAttempt.getAMRMToken();
        if (nextMasterKey.getMasterKey().getKeyId() !=
            appAttemptImpl.getAMRMTokenKeyId()) {
          LOG.info("The AMRMToken has been rolled-over. Send new AMRMToken back"
              + " to application: " + applicationId);
          amrmToken = rmContext.getAMRMTokenSecretManager()
              .createAndGetAMRMToken(appAttemptId);
          appAttemptImpl.setAMRMToken(amrmToken);
        }
        allocateResponse.setAMRMToken(org.apache.hadoop.yarn.api.records.Token
          .newInstance(amrmToken.getIdentifier(), amrmToken.getKind()
            .toString(), amrmToken.getPassword(), amrmToken.getService()
            .toString()));
      }

      /*
       * As we are updating the response inside the lock object so we don't
       * need to worry about unregister call occurring in between (which
       * removes the lock object).
       */
      lock.setAllocateResponse(allocateResponse);
      return allocateResponse;
    }    
  }
  
  //设置抢占信息
  private PreemptionMessage generatePreemptionMessage(Allocation allocation){
    PreemptionMessage pMsg = null;
    // assemble strict preemption request
    if (allocation.getStrictContainerPreemptions() != null) {
       pMsg =
        recordFactory.newRecordInstance(PreemptionMessage.class);
      StrictPreemptionContract pStrict =
          recordFactory.newRecordInstance(StrictPreemptionContract.class);
      Set<PreemptionContainer> pCont = new HashSet<PreemptionContainer>();
      for (ContainerId cId : allocation.getStrictContainerPreemptions()) {
        PreemptionContainer pc =
            recordFactory.newRecordInstance(PreemptionContainer.class);
        pc.setId(cId);
        pCont.add(pc);
      }
      pStrict.setContainers(pCont);
      pMsg.setStrictContract(pStrict);
    }

    // assemble negotiable preemption request
    if (allocation.getResourcePreemptions() != null &&
        allocation.getResourcePreemptions().size() > 0 &&
        allocation.getContainerPreemptions() != null &&
        allocation.getContainerPreemptions().size() > 0) {
      if (pMsg == null) {
        pMsg =
            recordFactory.newRecordInstance(PreemptionMessage.class);
      }
      PreemptionContract contract =
          recordFactory.newRecordInstance(PreemptionContract.class);
      Set<PreemptionContainer> pCont = new HashSet<PreemptionContainer>();
      for (ContainerId cId : allocation.getContainerPreemptions()) {
        PreemptionContainer pc =
            recordFactory.newRecordInstance(PreemptionContainer.class);
        pc.setId(cId);
        pCont.add(pc);
      }
      List<PreemptionResourceRequest> pRes = new ArrayList<PreemptionResourceRequest>();
      //FairScheduler中没有fungibleContainers和fungibleResources,可以参考CapacityScheduler.allocate() -> FiCaSchedulerApp.getAllocation()
      for (ResourceRequest crr : allocation.getResourcePreemptions()) {
        PreemptionResourceRequest prr =
            recordFactory.newRecordInstance(PreemptionResourceRequest.class);
        prr.setResourceRequest(crr);
        pRes.add(prr);
      }
      contract.setContainers(pCont);
      contract.setResourceRequest(pRes);
      pMsg.setContract(contract);
    }
    
    return pMsg;
  }

  //RM端维护的ApplicationMaster attempt信息
 
  public void registerAppAttempt(ApplicationAttemptId attemptId) {
    AllocateResponse response =
        recordFactory.newRecordInstance(AllocateResponse.class);
    // set response id to -1 before application master for the following
    // attemptID get registered
    response.setResponseId(-1);
    LOG.info("Registering app attempt : " + attemptId);
    responseMap.put(attemptId, new AllocateResponseLock(response));
    rmContext.getNMTokenSecretManager().registerApplicationAttempt(attemptId);
  }

  public void unregisterAttempt(ApplicationAttemptId attemptId) {
    LOG.info("Unregistering app attempt : " + attemptId);
    responseMap.remove(attemptId);
    rmContext.getNMTokenSecretManager().unregisterApplicationAttempt(attemptId);
  }

  public void refreshServiceAcls(Configuration configuration, 
      PolicyProvider policyProvider) {
    this.server.refreshServiceAclWithLoadedConfiguration(configuration,
        policyProvider);
  }
  
  @Override
  protected void serviceStop() throws Exception {
    if (this.server != null) {
      this.server.stop();
    }
    super.serviceStop();
  }
  
  public static class AllocateResponseLock {
    private AllocateResponse response;
    
    public AllocateResponseLock(AllocateResponse response) {
      this.response = response;
    }
    
    public synchronized AllocateResponse getAllocateResponse() {
      return response;
    }
    
    public synchronized void setAllocateResponse(AllocateResponse response) {
      this.response = response;
    }
  }

  @VisibleForTesting
  public Server getServer() {
    return this.server;
  }
}
