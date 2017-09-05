package org.apache.hadoop.yarn.sls.appmaster;

/**
 * Created by wencong on 17-4-14.
 */
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


import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.microsoft.philly.appmaster.AppMasterContainer;
import com.microsoft.philly.appmaster.ContainerResourceDescriptor;
import com.microsoft.philly.appmaster.TopologyAwareNodeAllocator;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateRequest;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.security.AMRMTokenIdentifier;
import org.apache.hadoop.yarn.server.resourcemanager.ResourceManager;
import org.apache.hadoop.yarn.server.utils.BuilderUtils;

import org.apache.hadoop.yarn.sls.scheduler.ContainerSimulator;
import org.apache.hadoop.yarn.sls.SLSRunner;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import sun.rmi.runtime.Log;

@Private
@Unstable
public class PhillyAMSimulator extends AMSimulator {
    /*
    Vocabulary Used:
    pending -> requests which are NOT yet sent to RM
    scheduled -> requests which are sent to RM but not yet assigned
    assigned -> requests which are assigned to a container
    completed -> request corresponding to which container has completed

    Maps are scheduled as soon as their requests are received. Reduces are
    scheduled when all maps have finished (not support slow-start currently).
    */
    private static final int PRIORITY_AM = 1;
    private static final int PRIORITY_GPU = 20;

    // pending Gpu containers
    // not even send out the resource request
    private LinkedList<ContainerSimulator> pendingGpuContainers =
            new LinkedList<ContainerSimulator>();

    // pending failed maps
    // useless now
    private LinkedList<ContainerSimulator> pendingFailedGpuContainers =
            new LinkedList<ContainerSimulator>();

    // scheduled Gpu container
    //
    private LinkedList<ContainerSimulator> scheduledGpuContainers =
            new LinkedList<ContainerSimulator>();

    // assigned Gpu containers
    private Map<ContainerId, ContainerSimulator> assignedGpuContainers =
            new HashMap<ContainerId, ContainerSimulator>();

    // all maps & reduces
    private LinkedList<ContainerSimulator> allGpus =
            new LinkedList<ContainerSimulator>();

    private Map<ContainerId, Container> allocatedContainer =
            new HashMap<ContainerId, Container>();

    private List<ContainerId> releaseContainer =
            new ArrayList<ContainerId>();

    // PhillyAM wrapper
    private PhillyAMWrapper phillyAM;
    // waiting for AM container
    private boolean isAMContainerRunning = false;
    private Container amContainer;
    // finished
    private boolean isFinished = false;
    // timeout
    private boolean alreadySetTimeout = false;
    private boolean enableAllocatingTimeout = false;
    private boolean enableYieldingTimeout = false;
    private long allocateTime, yieldTime;
    private long allocateTimeout, yieldTimeout;
    // need allocation
    private boolean needResourceallocation = false;
    // request node list
    private String connectivityDomain = null;
    private String nodeList = null;

    // gpu per container;
    private int gpuPerContainer;

    // resource for AM container
    private final static int MR_AM_CONTAINER_RESOURCE_MEMORY_MB = 1024;
    private final static int MR_AM_CONTAINER_RESOURCE_VCORES = 1;

    public final Logger LOG = Logger.getLogger(PhillyAMSimulator.class);

    public void init(int id, long heartbeatInterval,
                     List<ContainerSimulator> containerList, ResourceManager rm, YarnClient yarnClient, SLSRunner se,
                     long traceSubmitTime, long traceStartTime, long traceFinishTime, String user, String queue, int jobGpu,
                     boolean isTracked, String oldAppId) {
        super.init(id, heartbeatInterval, containerList, rm, yarnClient, se,
                traceSubmitTime, traceStartTime, traceFinishTime, user, queue, jobGpu,
                isTracked, oldAppId);
        amtype = "philly";

        // currently we don't support dynamic container size.
        // fixed 4 GPU per container
        // TODO
        gpuPerContainer = 4;
        if (jobGpu < gpuPerContainer){
            gpuPerContainer = jobGpu;
        }
        totalContainers = jobGpu / gpuPerContainer;

        // TODO: hack for debugging, need to fix it
        //totalContainers = pendingGpuContainers.size();

        // timeout
        alreadySetTimeout = false;
        enableAllocatingTimeout = false;
        enableYieldingTimeout = false;

        needResourceallocation = true;


        // for debug
        Logger.getRootLogger().setLevel(Level.ALL);
    }

    @Override
    public void firstStep() throws Exception {
        super.firstStep();

        requestAMContainer();
    }

    /**
     * send out request for AM container
     */
    protected void requestAMContainer()
            throws YarnException, IOException, InterruptedException {
        List<ResourceRequest> ask = new ArrayList<ResourceRequest>();
        ResourceRequest amRequest = createResourceRequest(
                BuilderUtils.newResource(MR_AM_CONTAINER_RESOURCE_MEMORY_MB,
                        MR_AM_CONTAINER_RESOURCE_VCORES),
                ResourceRequest.ANY, PRIORITY_AM, 1);
        ask.add(amRequest);
        LOG.debug(MessageFormat.format("Application {0} sends out allocate " +
                "request for its AM", appId));
        final AllocateRequest request = this.createAllocateRequest(ask);
        LOG.debug(MessageFormat.format("Application {0} am request: {1}", appId, request));
        UserGroupInformation ugi =
                UserGroupInformation.createRemoteUser(appAttemptId.toString());
        Token<AMRMTokenIdentifier> token = rm.getRMContext().getRMApps()
                .get(appAttemptId.getApplicationId())
                .getRMAppAttempt(appAttemptId).getAMRMToken();
        ugi.addTokenIdentifier(token.decodeIdentifier());
        AllocateResponse response = ugi.doAs(
                new PrivilegedExceptionAction<AllocateResponse>() {
                    @Override
                    public AllocateResponse run() throws Exception {
                        return rm.getApplicationMasterService().allocate(request);
                    }
                });
        if (response != null) {
            responseQueue.put(response);
        }
    }
    @Override
    protected void checkTimeOut(long currentTimeMS)
            throws InterruptedException, YarnException, IOException {
        if (isFinished) return;
        if (enableAllocatingTimeout && (currentTimeMS - allocateTime > allocateTimeout)) {
            if (assignedGpuContainers.size() != totalContainers) {

                // not all the request being achieved
                // back off,
                // cancel scheduled request

                String host = "/" + connectivityDomain + "/" + nodeList;
                List<ResourceRequest> cancelAsk = null;
                //List<ResourceRequest> cancelAsk = cancelRequests(scheduledGpuContainers, PRIORITY_GPU);
                if (cancelAsk == null)
                {
                    cancelAsk = new ArrayList<ResourceRequest>();
                }
                //
                // release all assigned resource
                releaseContainer.clear();
                for (Map.Entry<ContainerId, ContainerSimulator> entry: assignedGpuContainers.entrySet()){
                    Container cc = allocatedContainer.get(entry.getKey());
                    releaseContainer.add(cc.getId());
                }

                // use AM-RM protocol for allocation
                // leave the allocate list empty to upate the request for RM
                final AllocateRequest releaseRequest = createAllocateRequest(cancelAsk, releaseContainer);
                LOG.debug(MessageFormat.format("release request: {0}", releaseRequest));
                UserGroupInformation ugi =
                        UserGroupInformation.createRemoteUser(appAttemptId.toString());
                Token<AMRMTokenIdentifier> token = rm.getRMContext().getRMApps()
                        .get(appAttemptId.getApplicationId())
                        .getRMAppAttempt(appAttemptId).getAMRMToken();
                ugi.addTokenIdentifier(token.decodeIdentifier());
                AllocateResponse response = ugi.doAs(
                        new PrivilegedExceptionAction<AllocateResponse>() {
                            @Override
                            public AllocateResponse run() throws Exception {
                                return rm.getApplicationMasterService().allocate(releaseRequest);
                            }
                        });
                if (response != null) {
                    responseQueue.put(response);
                }

                // clean
                finishedContainers = 0;
                totalContainers = 0;
                isFinished = false;

                pendingGpuContainers.clear();
                pendingFailedGpuContainers.clear();
                scheduledGpuContainers.clear();
                //assignedGpuContainers.clear();
                allocatedContainer.clear();

                // set yield timeout
                enableAllocatingTimeout = false;
                enableYieldingTimeout = true;
                yieldTimeout = phillyAM.getYieldingTimeout();
                yieldTime = currentTimeMS;

                LOG.debug(MessageFormat.format("Application {0} allocating resource timeout for {1}. " +
                        "It should release all resource and yield for {2}.", appId, allocateTimeout, yieldTimeout));
            }
        }
        else if (enableYieldingTimeout && (currentTimeMS - yieldTime > yieldTimeout)) {

            LOG.debug(MessageFormat.format("Application {0} resume from yield.", appId));

            needResourceallocation = true;

            // send out allocating request
            pendingGpuContainers.addAll(allGpus);

            // set allocating timeout
            enableYieldingTimeout = false;
            alreadySetTimeout = false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void processResponseQueue(long currentTimeMS)
            throws InterruptedException, YarnException, IOException {
        // Check whether receive the am container
        if (!isAMContainerRunning) {
            if (!responseQueue.isEmpty()) {
                AllocateResponse response = responseQueue.take();
                if (response != null
                        && !response.getAllocatedContainers().isEmpty()) {
                    // Get AM container
                    Container container = response.getAllocatedContainers().get(0);
                    se.getNmMap().get(container.getNodeId())
                            .addNewContainer(container, -1L);
                    // Start AM container
                    amContainer = container;

                    // start PhillyAM wrapper
                    String amContainerHostname = se.getNmMap().get(container.getNodeId()).getNode().getHostName();
                    phillyAM = new PhillyAMWrapper(yarnClient, new AppMasterContainer(amContainerHostname));

                    LOG.debug(MessageFormat.format("Application {0} starts its " +
                            "AM container ({1}).", appId, amContainer.getId()));
                    isAMContainerRunning = true;
                }
            }
            return;
        }

        while (! responseQueue.isEmpty()) {
            AllocateResponse response = responseQueue.take();

            // check completed containers
            if (! response.getCompletedContainersStatuses().isEmpty()) {
                for (ContainerStatus cs : response.getCompletedContainersStatuses()) {
                    ContainerId containerId = cs.getContainerId();
                    if (cs.getExitStatus() == ContainerExitStatus.SUCCESS) {
                        if (assignedGpuContainers.containsKey(containerId)) {
                            LOG.debug(MessageFormat.format("Application {0} has 1 " +
                                    "gpu work finished: ({1}).", appId, containerId));
                            assignedGpuContainers.remove(containerId);
                            finishedContainers ++;
                        } else {
                            // am container released event
                            isFinished = true;
                            LOG.info(MessageFormat.format("Application {0} goes to finish.", appId));
                        }
                    } else {
                        // Wencong: currently ignore all killed; restart AM will trigger some bugs.
                        LOG.debug(MessageFormat.format("[containerId {1}] exit {1}", cs.getContainerId(), cs.getExitStatus()));
                        // container to be killed
                        if (assignedGpuContainers.containsKey(containerId)) {
                            LOG.debug(MessageFormat.format("Application {0} has one " +
                                    "gpu work killed ({1}).", appId, containerId));
                            pendingFailedGpuContainers.add(assignedGpuContainers.remove(containerId));
                        } else {
                            LOG.info(MessageFormat.format("Application {0}'s AM is " +
                                    "going to be killed. Restarting...", appId));
                            //restart();
                        }
                    }
                }
            }

            // check finished
            if (isAMContainerRunning &&
                    (finishedContainers == totalContainers)) {
                // to release the AM container
                se.getNmMap().get(amContainer.getNodeId())
                        .cleanupContainer(amContainer.getId());
                isAMContainerRunning = false;
                LOG.debug(MessageFormat.format("Application {0} sends out event " +
                        "to clean up its AM container.", appId));
                //isFinished = true;
                break;
            }

            // check allocated containers
            for (Container container : response.getAllocatedContainers()) {
                if (! scheduledGpuContainers.isEmpty()) {
                    ContainerSimulator cs = scheduledGpuContainers.remove();
                    LOG.debug(MessageFormat.format("Application {0} starts a " +
                            "launch a Gpu worker ({1}).", appId, container.getId()));
                    assignedGpuContainers.put(container.getId(), cs);
                    allocatedContainer.put(container.getId(), container);
                    // just reserve, so use Long.MAX_VALUE as the lifeTime
                    se.getNmMap().get(container.getNodeId())
                            .addNewContainer(container, Long.MAX_VALUE);

                    LOG.debug(MessageFormat.format("Application {0} assignedGpus {1}.", appId, assignedGpuContainers.size()));
                    if (assignedGpuContainers.size() == totalContainers)
                    {
                        simulateStartTimeMS = currentTimeMS - SLSRunner.getRunner().getStartTimeMS();
                        enableAllocatingTimeout = false;
                        LOG.debug(MessageFormat.format("Application {0} starts with all container available now.", appId));
                        // all GPU container assigned, start the work
                        for (Map.Entry<ContainerId, ContainerSimulator> entry: assignedGpuContainers.entrySet()){
                            Container cc = allocatedContainer.get(entry.getKey());
                            se.getNmMap().get(cc.getNodeId())
                                    .relaunchContainer(cc, cs.getLifeTime(), SLSRunner.NOW());
                        }
                    }

                    // set allocate timeout
                    /*
                    if (!alreadySetTimeout) {
                        alreadySetTimeout = true;
                        enableAllocatingTimeout = true;
                        allocateTimeout = phillyAM.getAllocatingTimeout();
                        LOG.debug(MessageFormat.format("Application {0} sets an allocating resource timeout: {1}.", appId, allocateTimeout));
                        allocateTime = currentTimeMS;
                    }
                    */
                }
            }
        }
    }

    /**
     * restart running because of the am container killed
     */
    private void restart()
            throws YarnException, IOException, InterruptedException {

        LOG.debug("AM restart");
        // clear
        isFinished = false;

        finishedContainers = 0;
        totalContainers = 0;

        pendingGpuContainers.clear();
        pendingFailedGpuContainers.clear();
        allocatedContainer.clear();
        pendingGpuContainers.addAll(allGpus);
        isAMContainerRunning = false;
        amContainer = null;
        // resent am container request
        requestAMContainer();
    }
    private static String strJoin(String[] arr, String sep) {
        StringBuilder sbStr = new StringBuilder();
        for (int i =0;i<arr.length;i++){
            if (i > 0) sbStr.append(sep);
            sbStr.append(arr[i]);
        }
        return sbStr.toString();
    }
    @Override
    protected void sendContainerRequest(long currentTimeMS)
            throws YarnException, IOException, InterruptedException {
        if (isFinished) {
            return;
        }
        // send out request
        List<ResourceRequest> ask = null;
        if (isAMContainerRunning) {
            if (needResourceallocation) {

                needResourceallocation = false;

                // choose rack-level preference
                TopologyAwareNodeAllocator.NodeGroup nodeGroup = phillyAM.getBestNodes(new ContainerResourceDescriptor("anyConnected", "", 0, 0, 4, false));
                connectivityDomain = nodeGroup.getConnectivityDomain();
                nodeList = strJoin(nodeGroup.getNodeArray(), ",");

                // determine container number
                pendingGpuContainers.clear();
                allGpus.clear();
                //assert (jobGpu % gpuPerContainer == 0);
                if (!SLSRunner.UnitTest()){
                    gpuPerContainer = nodeGroup.getNumGpusPerContainer();
                    if (jobGpu < gpuPerContainer){
                        gpuPerContainer = jobGpu;
                    }
                }
                int containerNum = jobGpu / gpuPerContainer;
                if (jobGpu % gpuPerContainer != 0) {
                    containerNum++;
                }

                for (int i = 0; i < containerNum; i++) {
                    final int gpuNumber = gpuPerContainer;
                    final int gpuToMem = 100 * 1024;
                    //final int gpuToMem = 1024;
                    String host = "/" + connectivityDomain + "/" + nodeList;
                    pendingGpuContainers.add(new ContainerSimulator(Resource.newInstance(gpuNumber * gpuToMem, gpuNumber),
                            traceFinishTimeMS - traceStartTimeMS, host, PRIORITY_GPU, "gpu"));
                }
                allGpus.addAll(pendingGpuContainers);
                totalContainers = pendingGpuContainers.size();


                ask = packageRequests(pendingGpuContainers, PRIORITY_GPU);
                LOG.debug(MessageFormat.format("Application {0} sends out request for {1} gpu workers."
                        , appId, pendingGpuContainers.size()));
                scheduledGpuContainers.addAll(pendingGpuContainers);
                pendingGpuContainers.clear();

            } else if (! pendingFailedGpuContainers.isEmpty() && scheduledGpuContainers.isEmpty()) {
                // Wencong: should never go into this branch
                assert(false);

                ask = packageRequests(pendingFailedGpuContainers, PRIORITY_GPU);
                LOG.debug(MessageFormat.format("Application {0} sends out " +
                                "requests for {1} failed gpu workers.", appId,
                        pendingFailedGpuContainers.size()));
                scheduledGpuContainers.addAll(pendingFailedGpuContainers);
                pendingFailedGpuContainers.clear();
            }
        }
        if (ask == null) {
            ask = new ArrayList<ResourceRequest>();
        }

        final AllocateRequest request = createAllocateRequest(ask);
        if (totalContainers == 0) {
            request.setProgress(1.0f);
        } else {
            request.setProgress((float) finishedContainers / totalContainers);
        }
        LOG.debug(MessageFormat.format("resource request: {0}", request));
        // use AM-RM protocol for allocation
        UserGroupInformation ugi =
                UserGroupInformation.createRemoteUser(appAttemptId.toString());
        Token<AMRMTokenIdentifier> token = rm.getRMContext().getRMApps()
                .get(appAttemptId.getApplicationId())
                .getRMAppAttempt(appAttemptId).getAMRMToken();
        ugi.addTokenIdentifier(token.decodeIdentifier());
        AllocateResponse response = ugi.doAs(
                new PrivilegedExceptionAction<AllocateResponse>() {
                    @Override
                    public AllocateResponse run() throws Exception {
                        return rm.getApplicationMasterService().allocate(request);
                    }
                });
        if (response != null) {
            responseQueue.put(response);
        }
    }

    @Override
    protected void checkStop(long currentTimeMS) {
        if (isFinished) {
            super.setEndTime(SLSRunner.NOW());
        }
    }

    @Override
    public void lastStep() throws Exception {
        super.lastStep();

        // clear data structures
        allGpus.clear();
        assignedGpuContainers.clear();
        pendingFailedGpuContainers.clear();
        pendingGpuContainers.clear();
        scheduledGpuContainers.clear();
        allocatedContainer.clear();
        responseQueue.clear();
    }
}
