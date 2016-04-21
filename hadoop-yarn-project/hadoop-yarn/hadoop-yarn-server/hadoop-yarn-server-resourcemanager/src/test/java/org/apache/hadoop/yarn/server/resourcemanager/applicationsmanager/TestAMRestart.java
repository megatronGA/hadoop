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

package org.apache.hadoop.yarn.server.resourcemanager.applicationsmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.records.ApplicationAccessType;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerExitStatus;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.NMToken;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.Dispatcher;
import org.apache.hadoop.yarn.event.DrainDispatcher;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.server.api.protocolrecords.NMContainerStatus;
import org.apache.hadoop.yarn.server.resourcemanager.MockAM;
import org.apache.hadoop.yarn.server.resourcemanager.MockNM;
import org.apache.hadoop.yarn.server.resourcemanager.MockRM;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.MemoryRMStateStore;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.records.ApplicationStateData;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppState;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptState;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerState;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.AbstractYarnScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerApplicationAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.TestSchedulerUtils;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CapacityScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.SchedulerEvent;
import org.apache.hadoop.yarn.server.utils.BuilderUtils;
import org.apache.hadoop.yarn.util.ControlledClock;
import org.apache.hadoop.yarn.util.Records;
import org.apache.hadoop.yarn.util.resource.Resources;
import org.junit.Assert;
import org.junit.Test;

public class TestAMRestart {

  @Test(timeout = 30000)
  public void testAMRestartWithExistingContainers() throws Exception {
    YarnConfiguration conf = new YarnConfiguration();
    conf.setInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS, 2);

    MockRM rm1 = new MockRM(conf);
    rm1.start();
    RMApp app1 =
        rm1.submitApp(200, "name", "user",
          new HashMap<ApplicationAccessType, String>(), false, "default", -1,
          null, "MAPREDUCE", false, true);
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 10240, rm1.getResourceTrackerService());
    nm1.registerNode();
    MockNM nm2 =
        new MockNM("127.0.0.1:2351", 4089, rm1.getResourceTrackerService());
    nm2.registerNode();

    MockAM am1 = MockRM.launchAndRegisterAM(app1, rm1, nm1);
    int NUM_CONTAINERS = 3;
    allocateContainers(nm1, am1, NUM_CONTAINERS);

    // launch the 2nd container, for testing running container transferred.
    nm1.nodeHeartbeat(am1.getApplicationAttemptId(), 2, ContainerState.RUNNING);
    ContainerId containerId2 =
        ContainerId.newContainerId(am1.getApplicationAttemptId(), 2);
    rm1.waitForState(nm1, containerId2, RMContainerState.RUNNING);

    // launch the 3rd container, for testing container allocated by previous
    // attempt is completed by the next new attempt/
    nm1.nodeHeartbeat(am1.getApplicationAttemptId(), 3, ContainerState.RUNNING);
    ContainerId containerId3 =
        ContainerId.newContainerId(am1.getApplicationAttemptId(), 3);
    rm1.waitForState(nm1, containerId3, RMContainerState.RUNNING);

    // 4th container still in AQUIRED state. for testing Acquired container is
    // always killed.
    ContainerId containerId4 =
        ContainerId.newContainerId(am1.getApplicationAttemptId(), 4);
    rm1.waitForState(nm1, containerId4, RMContainerState.ACQUIRED);

    // 5th container is in Allocated state. for testing allocated container is
    // always killed.
    am1.allocate("127.0.0.1", 1024, 1, new ArrayList<ContainerId>());
    nm1.nodeHeartbeat(true);
    ContainerId containerId5 =
        ContainerId.newContainerId(am1.getApplicationAttemptId(), 5);
    rm1.waitForContainerAllocated(nm1, containerId5);
    rm1.waitForState(nm1, containerId5, RMContainerState.ALLOCATED);

    // 6th container is in Reserved state.
    am1.allocate("127.0.0.1", 6000, 1, new ArrayList<ContainerId>());
    ContainerId containerId6 =
        ContainerId.newContainerId(am1.getApplicationAttemptId(), 6);
    nm1.nodeHeartbeat(true);
    SchedulerApplicationAttempt schedulerAttempt =
        ((AbstractYarnScheduler) rm1.getResourceScheduler())
          .getCurrentAttemptForContainer(containerId6);
    while (schedulerAttempt.getReservedContainers().isEmpty()) {
      System.out.println("Waiting for container " + containerId6
          + " to be reserved.");
      nm1.nodeHeartbeat(true);
      Thread.sleep(200);
    }
    // assert containerId6 is reserved.
    Assert.assertEquals(containerId6, schedulerAttempt.getReservedContainers()
      .get(0).getContainerId());

    // fail the AM by sending CONTAINER_FINISHED event without registering.
    nm1.nodeHeartbeat(am1.getApplicationAttemptId(), 1, ContainerState.COMPLETE);
    am1.waitForState(RMAppAttemptState.FAILED);

    // wait for some time. previous AM's running containers should still remain
    // in scheduler even though am failed
    Thread.sleep(3000);
    rm1.waitForState(nm1, containerId2, RMContainerState.RUNNING);
    // acquired/allocated containers are cleaned up.
    Assert.assertNull(rm1.getResourceScheduler().getRMContainer(containerId4));
    Assert.assertNull(rm1.getResourceScheduler().getRMContainer(containerId5));

    // wait for app to start a new attempt.
    rm1.waitForState(app1.getApplicationId(), RMAppState.ACCEPTED);
    // assert this is a new AM.
    ApplicationAttemptId newAttemptId =
        app1.getCurrentAppAttempt().getAppAttemptId();
    Assert.assertFalse(newAttemptId.equals(am1.getApplicationAttemptId()));

    // launch the new AM
    RMAppAttempt attempt2 = app1.getCurrentAppAttempt();
    nm1.nodeHeartbeat(true);
    MockAM am2 = rm1.sendAMLaunched(attempt2.getAppAttemptId());
    RegisterApplicationMasterResponse registerResponse =
        am2.registerAppAttempt();

    // Assert two containers are running: container2 and container3;
    Assert.assertEquals(2, registerResponse.getContainersFromPreviousAttempts()
      .size());
    boolean containerId2Exists = false, containerId3Exists = false;
    for (Container container : registerResponse
      .getContainersFromPreviousAttempts()) {
      if (container.getId().equals(containerId2)) {
        containerId2Exists = true;
      }
      if (container.getId().equals(containerId3)) {
        containerId3Exists = true;
      }
    }
    Assert.assertTrue(containerId2Exists && containerId3Exists);
    rm1.waitForState(app1.getApplicationId(), RMAppState.RUNNING);

    // complete container by sending the container complete event which has earlier
    // attempt's attemptId
    nm1.nodeHeartbeat(am1.getApplicationAttemptId(), 3, ContainerState.COMPLETE);

    // Even though the completed container containerId3 event was sent to the
    // earlier failed attempt, new RMAppAttempt can also capture this container
    // info.
    // completed containerId4 is also transferred to the new attempt.
    RMAppAttempt newAttempt =
        app1.getRMAppAttempt(am2.getApplicationAttemptId());
    // 4 containers finished, acquired/allocated/reserved/completed.
    waitForContainersToFinish(4, newAttempt);
    boolean container3Exists = false, container4Exists = false, container5Exists =
        false, container6Exists = false;
    for(ContainerStatus status :  newAttempt.getJustFinishedContainers()) {
      if(status.getContainerId().equals(containerId3)) {
        // containerId3 is the container ran by previous attempt but finished by the
        // new attempt.
        container3Exists = true;
      }
      if (status.getContainerId().equals(containerId4)) {
        // containerId4 is the Acquired Container killed by the previous attempt,
        // it's now inside new attempt's finished container list.
        container4Exists = true;
      }
      if (status.getContainerId().equals(containerId5)) {
        // containerId5 is the Allocated container killed by previous failed attempt.
        container5Exists = true;
      }
      if (status.getContainerId().equals(containerId6)) {
        // containerId6 is the reserved container killed by previous failed attempt.
        container6Exists = true;
      }
    }
    Assert.assertTrue(container3Exists && container4Exists && container5Exists
        && container6Exists);

    // New SchedulerApplicationAttempt also has the containers info.
    rm1.waitForState(nm1, containerId2, RMContainerState.RUNNING);

    // record the scheduler attempt for testing.
    SchedulerApplicationAttempt schedulerNewAttempt =
        ((AbstractYarnScheduler) rm1.getResourceScheduler())
          .getCurrentAttemptForContainer(containerId2);
    // finish this application
    MockRM.finishAMAndVerifyAppState(app1, rm1, nm1, am2);

    // the 2nd attempt released the 1st attempt's running container, when the
    // 2nd attempt finishes.
    Assert.assertFalse(schedulerNewAttempt.getLiveContainers().contains(
      containerId2));
    // all 4 normal containers finished.
    System.out.println("New attempt's just finished containers: "
        + newAttempt.getJustFinishedContainers());
    waitForContainersToFinish(5, newAttempt);
    rm1.stop();
  }

  private List<Container> allocateContainers(MockNM nm1, MockAM am1,
      int NUM_CONTAINERS) throws Exception {
    // allocate NUM_CONTAINERS containers
    am1.allocate("127.0.0.1", 1024, NUM_CONTAINERS,
      new ArrayList<ContainerId>());
    nm1.nodeHeartbeat(true);

    // wait for containers to be allocated.
    List<Container> containers =
        am1.allocate(new ArrayList<ResourceRequest>(),
          new ArrayList<ContainerId>()).getAllocatedContainers();
    while (containers.size() != NUM_CONTAINERS) {
      nm1.nodeHeartbeat(true);
      containers.addAll(am1.allocate(new ArrayList<ResourceRequest>(),
        new ArrayList<ContainerId>()).getAllocatedContainers());
      Thread.sleep(200);
    }

    Assert.assertEquals("Did not get all containers allocated",
        NUM_CONTAINERS, containers.size());
    return containers;
  }

  private void waitForContainersToFinish(int expectedNum, RMAppAttempt attempt)
      throws InterruptedException {
    int count = 0;
    while (attempt.getJustFinishedContainers().size() < expectedNum
        && count < 500) {
      Thread.sleep(100);
      count++;
    }
  }

  @Test(timeout = 30000)
  public void testNMTokensRebindOnAMRestart() throws Exception {
    YarnConfiguration conf = new YarnConfiguration();
    conf.setInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS, 3);
    // To prevent test from blacklisting nm1 for AM, we sit threshold to half
    // of 2 nodes which is 1
    conf.setFloat(YarnConfiguration.AM_BLACKLISTING_DISABLE_THRESHOLD, 0.5f);

    MockRM rm1 = new MockRM(conf);
    rm1.start();
    RMApp app1 =
        rm1.submitApp(200, "myname", "myuser",
          new HashMap<ApplicationAccessType, String>(), false, "default", -1,
          null, "MAPREDUCE", false, true);
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 8000, rm1.getResourceTrackerService());
    nm1.registerNode();
    MockNM nm2 =
        new MockNM("127.1.1.1:4321", 8000, rm1.getResourceTrackerService());
    nm2.registerNode();
    MockAM am1 = MockRM.launchAndRegisterAM(app1, rm1, nm1);

    List<Container> containers = new ArrayList<Container>();
    // nmTokens keeps track of all the nmTokens issued in the allocate call.
    List<NMToken> expectedNMTokens = new ArrayList<NMToken>();

    // am1 allocate 2 container on nm1.
    // first container
    while (true) {
      AllocateResponse response =
          am1.allocate("127.0.0.1", 2000, 2,
            new ArrayList<ContainerId>());
      nm1.nodeHeartbeat(true);
      containers.addAll(response.getAllocatedContainers());
      expectedNMTokens.addAll(response.getNMTokens());
      if (containers.size() == 2) {
        break;
      }
      Thread.sleep(200);
      System.out.println("Waiting for container to be allocated.");
    }
    // launch the container-2
    nm1.nodeHeartbeat(am1.getApplicationAttemptId(), 2, ContainerState.RUNNING);
    ContainerId containerId2 =
        ContainerId.newContainerId(am1.getApplicationAttemptId(), 2);
    rm1.waitForState(nm1, containerId2, RMContainerState.RUNNING);
    // launch the container-3
    nm1.nodeHeartbeat(am1.getApplicationAttemptId(), 3, ContainerState.RUNNING);
    ContainerId containerId3 =
        ContainerId.newContainerId(am1.getApplicationAttemptId(), 3);
    rm1.waitForState(nm1, containerId3, RMContainerState.RUNNING);
    
    // fail am1
    nm1.nodeHeartbeat(am1.getApplicationAttemptId(), 1, ContainerState.COMPLETE);
    am1.waitForState(RMAppAttemptState.FAILED);
    rm1.waitForState(app1.getApplicationId(), RMAppState.ACCEPTED);

    // restart the am
    MockAM am2 = MockRM.launchAM(app1, rm1, nm1);
    RegisterApplicationMasterResponse registerResponse =
        am2.registerAppAttempt();
    rm1.waitForState(am2.getApplicationAttemptId(), RMAppAttemptState.RUNNING);

    // check am2 get the nm token from am1.
    Assert.assertEquals(expectedNMTokens.size(),
        registerResponse.getNMTokensFromPreviousAttempts().size());
    for (int i = 0; i < expectedNMTokens.size(); i++) {
      Assert.assertTrue(expectedNMTokens.get(i)
          .equals(registerResponse.getNMTokensFromPreviousAttempts().get(i)));
    }

    // am2 allocate 1 container on nm2
    containers = new ArrayList<Container>();
    while (true) {
      AllocateResponse allocateResponse =
          am2.allocate("127.1.1.1", 4000, 1,
            new ArrayList<ContainerId>());
      nm2.nodeHeartbeat(true);
      containers.addAll(allocateResponse.getAllocatedContainers());
      expectedNMTokens.addAll(allocateResponse.getNMTokens());
      if (containers.size() == 1) {
        break;
      }
      Thread.sleep(200);
      System.out.println("Waiting for container to be allocated.");
    }
    nm1.nodeHeartbeat(am2.getApplicationAttemptId(), 2, ContainerState.RUNNING);
    ContainerId am2ContainerId2 =
        ContainerId.newContainerId(am2.getApplicationAttemptId(), 2);
    rm1.waitForState(nm1, am2ContainerId2, RMContainerState.RUNNING);

    // fail am2.
    nm1.nodeHeartbeat(am2.getApplicationAttemptId(), 1, ContainerState.COMPLETE);
    am2.waitForState(RMAppAttemptState.FAILED);
    rm1.waitForState(app1.getApplicationId(), RMAppState.ACCEPTED);

    // restart am
    MockAM am3 = MockRM.launchAM(app1, rm1, nm1);
    registerResponse = am3.registerAppAttempt();
    rm1.waitForState(am3.getApplicationAttemptId(), RMAppAttemptState.RUNNING);

    // check am3 get the NM token from both am1 and am2;
    List<NMToken> transferredTokens = registerResponse.getNMTokensFromPreviousAttempts();
    Assert.assertEquals(2, transferredTokens.size());
    Assert.assertTrue(transferredTokens.containsAll(expectedNMTokens));
    rm1.stop();
  }

  @Test(timeout = 100000)
  public void testAMBlacklistPreventsRestartOnSameNode() throws Exception {
    YarnConfiguration conf = new YarnConfiguration();
    conf.setClass(YarnConfiguration.RM_SCHEDULER, CapacityScheduler.class,
        ResourceScheduler.class);
    conf.setBoolean(YarnConfiguration.AM_BLACKLISTING_ENABLED, true);
    testAMBlacklistPreventRestartOnSameNode(false, conf);
  }

  @Test(timeout = 100000)
  public void testAMBlacklistPreventsRestartOnSameNodeForMinicluster()
      throws Exception {
    YarnConfiguration conf = new YarnConfiguration();
    conf.setBoolean(YarnConfiguration.AM_BLACKLISTING_ENABLED, true);
    conf.setBoolean(YarnConfiguration.RM_SCHEDULER_INCLUDE_PORT_IN_NODE_NAME,
        true);
    testAMBlacklistPreventRestartOnSameNode(false, conf);
  }

  @Test(timeout = 100000)
  public void testAMBlacklistPreemption() throws Exception {
    YarnConfiguration conf = new YarnConfiguration();
    conf.setBoolean(YarnConfiguration.AM_BLACKLISTING_ENABLED, true);
    // disable the float so it is possible to blacklist the entire cluster
    conf.setFloat(YarnConfiguration.AM_BLACKLISTING_DISABLE_THRESHOLD, 1.5f);
    // since the exit status is PREEMPTED, it should not lead to the node being
    // blacklisted
    testAMBlacklistPreventRestartOnSameNode(true, conf);
  }

  /**
   * Tests AM blacklisting. In the multi-node mode (i.e. singleNode = false),
   * it tests the blacklisting behavior so that the AM container gets allocated
   * on the node that is not blacklisted. In the single-node mode, it tests the
   * PREEMPTED status to see if the AM container can continue to be scheduled.
   */
  private void testAMBlacklistPreventRestartOnSameNode(boolean singleNode,
      YarnConfiguration conf) throws Exception {
    MemoryRMStateStore memStore = new MemoryRMStateStore();
    memStore.init(conf);
    final DrainDispatcher dispatcher = new DrainDispatcher();
    MockRM rm1 = new MockRM(conf, memStore) {
      @Override
      protected EventHandler<SchedulerEvent> createSchedulerEventDispatcher() {
        return new SchedulerEventDispatcher(this.scheduler) {
          @Override
          public void handle(SchedulerEvent event) {
            scheduler.handle(event);
          }
        };
      }

      @Override
      protected Dispatcher createDispatcher() {
        return dispatcher;
      }
    };

    rm1.start();

    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 8000, rm1.getResourceTrackerService());
    nm1.registerNode();

    MockNM nm2 = null;
    if (!singleNode) {
      nm2 =
          new MockNM("127.0.0.2:2345", 8000, rm1.getResourceTrackerService());
      nm2.registerNode();
    }

    RMApp app1 = rm1.submitApp(200);

    MockAM am1 = MockRM.launchAndRegisterAM(app1, rm1, nm1);
    CapacityScheduler scheduler =
        (CapacityScheduler) rm1.getResourceScheduler();
    ContainerId amContainer =
        ContainerId.newContainerId(am1.getApplicationAttemptId(), 1);
    // Preempt the first attempt;
    RMContainer rmContainer = scheduler.getRMContainer(amContainer);
    NodeId nodeWhereAMRan = rmContainer.getAllocatedNode();

    MockNM currentNode, otherNode;
    if (singleNode) {
      Assert.assertEquals(nm1.getNodeId(), nodeWhereAMRan);
      currentNode = nm1;
      otherNode = null; // not applicable
    } else {
      if (nodeWhereAMRan == nm1.getNodeId()) {
        currentNode = nm1;
        otherNode = nm2;
      } else {
        currentNode = nm2;
        otherNode = nm1;
      }
    }

    // set the exist status to test
    // any status other than SUCCESS and PREEMPTED should cause the node to be
    // blacklisted
    int exitStatus = singleNode ?
            ContainerExitStatus.PREEMPTED :
            ContainerExitStatus.INVALID;
    ContainerStatus containerStatus =
        BuilderUtils.newContainerStatus(amContainer, ContainerState.COMPLETE,
            "", exitStatus, Resources.createResource(200));
    currentNode.containerStatus(containerStatus);
    am1.waitForState(RMAppAttemptState.FAILED);
    rm1.waitForState(app1.getApplicationId(), RMAppState.ACCEPTED);

    // restart the am
    RMAppAttempt attempt = MockRM.waitForAttemptScheduled(app1, rm1);
    System.out.println("Launch AM " + attempt.getAppAttemptId());



    currentNode.nodeHeartbeat(true);
    dispatcher.await();

    if (!singleNode) {
      Assert.assertEquals(
          "AppAttemptState should still be SCHEDULED if currentNode is " +
          "blacklisted correctly",
          RMAppAttemptState.SCHEDULED,
          attempt.getAppAttemptState());

      otherNode.nodeHeartbeat(true);
      dispatcher.await();
    }

    MockAM am2 = rm1.sendAMLaunched(attempt.getAppAttemptId());
    rm1.waitForState(attempt.getAppAttemptId(), RMAppAttemptState.LAUNCHED);
    amContainer =
        ContainerId.newContainerId(am2.getApplicationAttemptId(), 1);
    rmContainer = scheduler.getRMContainer(amContainer);
    nodeWhereAMRan = rmContainer.getAllocatedNode();
    if (singleNode) {
      // with preemption, the node should not be blacklisted and should get the
      // assignment (with a single node)
      Assert.assertEquals(
          "AM should still have been able to run on the same node",
          currentNode.getNodeId(), nodeWhereAMRan);
    } else {
      // with a failed status, the other node should receive the assignment
      Assert.assertEquals(
          "After blacklisting AM should have run on the other node",
          otherNode.getNodeId(), nodeWhereAMRan);

      am2.registerAppAttempt();
      rm1.waitForState(app1.getApplicationId(), RMAppState.RUNNING);

      List<Container> allocatedContainers =
          allocateContainers(currentNode, am2, 1);
      Assert.assertEquals(
          "Even though AM is blacklisted from the node, application can " +
          "still allocate containers there",
          currentNode.getNodeId(), allocatedContainers.get(0).getNodeId());
    }
  }


  // AM container preempted, nm disk failure
  // should not be counted towards AM max retry count.
  @Test(timeout = 100000)
  public void testShouldNotCountFailureToMaxAttemptRetry() throws Exception {
    YarnConfiguration conf = new YarnConfiguration();
    conf.setClass(YarnConfiguration.RM_SCHEDULER, CapacityScheduler.class,
      ResourceScheduler.class);
    // explicitly set max-am-retry count as 1.
    conf.setInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS, 1);
    conf.setBoolean(YarnConfiguration.RECOVERY_ENABLED, true);
    conf.set(YarnConfiguration.RM_STORE, MemoryRMStateStore.class.getName());
    MemoryRMStateStore memStore = new MemoryRMStateStore();
    memStore.init(conf);
    MockRM rm1 = new MockRM(conf, memStore);
    rm1.start();
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 8000, rm1.getResourceTrackerService());
    nm1.registerNode();
    RMApp app1 = rm1.submitApp(200);
    RMAppAttempt attempt1 = app1.getCurrentAppAttempt();
    MockAM am1 = MockRM.launchAndRegisterAM(app1, rm1, nm1);
    CapacityScheduler scheduler =
        (CapacityScheduler) rm1.getResourceScheduler();
    ContainerId amContainer =
        ContainerId.newContainerId(am1.getApplicationAttemptId(), 1);
    // Preempt the first attempt;
    scheduler.markContainerForKillable(scheduler.getRMContainer(amContainer));

    am1.waitForState(RMAppAttemptState.FAILED);
    TestSchedulerUtils.waitSchedulerApplicationAttemptStopped(scheduler,
        am1.getApplicationAttemptId());

    Assert.assertTrue(! attempt1.shouldCountTowardsMaxAttemptRetry());
    rm1.waitForState(app1.getApplicationId(), RMAppState.ACCEPTED);
    ApplicationStateData appState =
        memStore.getState().getApplicationState().get(app1.getApplicationId());
    // AM should be restarted even though max-am-attempt is 1.
    MockAM am2 =
        rm1.waitForNewAMToLaunchAndRegister(app1.getApplicationId(), 2, nm1);
    RMAppAttempt attempt2 = app1.getCurrentAppAttempt();
    Assert.assertTrue(((RMAppAttemptImpl) attempt2).mayBeLastAttempt());

    // Preempt the second attempt.
    ContainerId amContainer2 =
        ContainerId.newContainerId(am2.getApplicationAttemptId(), 1);
    scheduler.markContainerForKillable(scheduler.getRMContainer(amContainer2));

    am2.waitForState(RMAppAttemptState.FAILED);
    TestSchedulerUtils.waitSchedulerApplicationAttemptStopped(scheduler,
        am2.getApplicationAttemptId());

    Assert.assertTrue(! attempt2.shouldCountTowardsMaxAttemptRetry());
    rm1.waitForState(app1.getApplicationId(), RMAppState.ACCEPTED);
    MockAM am3 =
        rm1.waitForNewAMToLaunchAndRegister(app1.getApplicationId(), 3, nm1);
    RMAppAttempt attempt3 = app1.getCurrentAppAttempt();
    Assert.assertTrue(((RMAppAttemptImpl) attempt3).mayBeLastAttempt());

    // mimic NM disk_failure
    ContainerStatus containerStatus = Records.newRecord(ContainerStatus.class);
    containerStatus.setContainerId(attempt3.getMasterContainer().getId());
    containerStatus.setDiagnostics("mimic NM disk_failure");
    containerStatus.setState(ContainerState.COMPLETE);
    containerStatus.setExitStatus(ContainerExitStatus.DISKS_FAILED);
    Map<ApplicationId, List<ContainerStatus>> conts =
        new HashMap<ApplicationId, List<ContainerStatus>>();
    conts.put(app1.getApplicationId(),
      Collections.singletonList(containerStatus));
    nm1.nodeHeartbeat(conts, true);

    am3.waitForState(RMAppAttemptState.FAILED);
    TestSchedulerUtils.waitSchedulerApplicationAttemptStopped(scheduler,
        am3.getApplicationAttemptId());

    Assert.assertTrue(! attempt3.shouldCountTowardsMaxAttemptRetry());
    Assert.assertEquals(ContainerExitStatus.DISKS_FAILED,
      appState.getAttempt(am3.getApplicationAttemptId())
        .getAMContainerExitStatus());

    rm1.waitForState(app1.getApplicationId(), RMAppState.ACCEPTED);
    MockAM am4 =
        rm1.waitForNewAMToLaunchAndRegister(app1.getApplicationId(), 4, nm1);
    RMAppAttempt attempt4 = app1.getCurrentAppAttempt();
    Assert.assertTrue(((RMAppAttemptImpl) attempt4).mayBeLastAttempt());

    // create second NM, and register to rm1
    MockNM nm2 =
        new MockNM("127.0.0.1:2234", 8000, rm1.getResourceTrackerService());
    nm2.registerNode();
    // nm1 heartbeats to report unhealthy
    // This will mimic ContainerExitStatus.ABORT
    nm1.nodeHeartbeat(false);
    am4.waitForState(RMAppAttemptState.FAILED);
    TestSchedulerUtils.waitSchedulerApplicationAttemptStopped(scheduler,
        am4.getApplicationAttemptId());

    Assert.assertTrue(! attempt4.shouldCountTowardsMaxAttemptRetry());
    Assert.assertEquals(ContainerExitStatus.ABORTED,
      appState.getAttempt(am4.getApplicationAttemptId())
        .getAMContainerExitStatus());
    // launch next AM in nm2
    nm2.nodeHeartbeat(true);
    MockAM am5 =
        rm1.waitForNewAMToLaunchAndRegister(app1.getApplicationId(), 5, nm2);
    RMAppAttempt attempt5 = app1.getCurrentAppAttempt();
    Assert.assertTrue(((RMAppAttemptImpl) attempt5).mayBeLastAttempt());
    // fail the AM normally
    nm2
      .nodeHeartbeat(am5.getApplicationAttemptId(), 1, ContainerState.COMPLETE);
    am5.waitForState(RMAppAttemptState.FAILED);
    TestSchedulerUtils.waitSchedulerApplicationAttemptStopped(scheduler,
        am5.getApplicationAttemptId());

    Assert.assertTrue(attempt5.shouldCountTowardsMaxAttemptRetry());

    // AM should not be restarted.
    rm1.waitForState(app1.getApplicationId(), RMAppState.FAILED);
    Assert.assertEquals(5, app1.getAppAttempts().size());
    rm1.stop();
  }

  // Test RM restarts after AM container is preempted, new RM should not count
  // AM preemption failure towards the max-retry-account and should be able to
  // re-launch the AM.
  @Test(timeout = 20000)
  public void testPreemptedAMRestartOnRMRestart() throws Exception {
    YarnConfiguration conf = new YarnConfiguration();
    conf.setClass(YarnConfiguration.RM_SCHEDULER, CapacityScheduler.class,
      ResourceScheduler.class);
    conf.setBoolean(YarnConfiguration.RECOVERY_ENABLED, true);
    conf.setBoolean(YarnConfiguration.RM_WORK_PRESERVING_RECOVERY_ENABLED, false);

    conf.set(YarnConfiguration.RM_STORE, MemoryRMStateStore.class.getName());
    // explicitly set max-am-retry count as 1.
    conf.setInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS, 1);
    MemoryRMStateStore memStore = new MemoryRMStateStore();
    memStore.init(conf);

    MockRM rm1 = new MockRM(conf, memStore);
    rm1.start();
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 8000, rm1.getResourceTrackerService());
    nm1.registerNode();
    RMApp app1 = rm1.submitApp(200);
    RMAppAttempt attempt1 = app1.getCurrentAppAttempt();
    MockAM am1 = MockRM.launchAndRegisterAM(app1, rm1, nm1);
    CapacityScheduler scheduler =
        (CapacityScheduler) rm1.getResourceScheduler();
    ContainerId amContainer =
        ContainerId.newContainerId(am1.getApplicationAttemptId(), 1);

    // Forcibly preempt the am container;
    scheduler.markContainerForKillable(scheduler.getRMContainer(amContainer));

    am1.waitForState(RMAppAttemptState.FAILED);
    Assert.assertTrue(! attempt1.shouldCountTowardsMaxAttemptRetry());
    rm1.waitForState(app1.getApplicationId(), RMAppState.ACCEPTED);

    // state store has 1 attempt stored.
    ApplicationStateData appState =
        memStore.getState().getApplicationState().get(app1.getApplicationId());
    Assert.assertEquals(1, appState.getAttemptCount());
    // attempt stored has the preempted container exit status.
    Assert.assertEquals(ContainerExitStatus.PREEMPTED,
      appState.getAttempt(am1.getApplicationAttemptId())
        .getAMContainerExitStatus());
    // Restart rm.
    MockRM rm2 = new MockRM(conf, memStore);
    nm1.setResourceTrackerService(rm2.getResourceTrackerService());
    nm1.registerNode();
    rm2.start();

    // Restarted RM should re-launch the am.
    MockAM am2 =
        rm2.waitForNewAMToLaunchAndRegister(app1.getApplicationId(), 2, nm1);
    MockRM.finishAMAndVerifyAppState(app1, rm2, nm1, am2);
    RMAppAttempt attempt2 =
        rm2.getRMContext().getRMApps().get(app1.getApplicationId())
          .getCurrentAppAttempt();
    Assert.assertTrue(attempt2.shouldCountTowardsMaxAttemptRetry());
    Assert.assertEquals(ContainerExitStatus.INVALID,
      appState.getAttempt(am2.getApplicationAttemptId())
        .getAMContainerExitStatus());
    rm1.stop();
    rm2.stop();
  }

  // Test regular RM restart/failover, new RM should not count
  // AM failure towards the max-retry-account and should be able to
  // re-launch the AM.
  @Test(timeout = 50000)
  public void testRMRestartOrFailoverNotCountedForAMFailures()
      throws Exception {
    YarnConfiguration conf = new YarnConfiguration();
    conf.setClass(YarnConfiguration.RM_SCHEDULER, CapacityScheduler.class,
      ResourceScheduler.class);
    conf.setBoolean(YarnConfiguration.RECOVERY_ENABLED, true);
    conf.setBoolean(YarnConfiguration.RM_WORK_PRESERVING_RECOVERY_ENABLED, false);

    conf.set(YarnConfiguration.RM_STORE, MemoryRMStateStore.class.getName());
    // explicitly set max-am-retry count as 1.
    conf.setInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS, 1);
    MemoryRMStateStore memStore = new MemoryRMStateStore();
    memStore.init(conf);

    MockRM rm1 = new MockRM(conf, memStore);
    rm1.start();
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 8000, rm1.getResourceTrackerService());
    nm1.registerNode();
    RMApp app1 = rm1.submitApp(200);
    // AM should be restarted even though max-am-attempt is 1.
    MockAM am1 = MockRM.launchAndRegisterAM(app1, rm1, nm1);
    RMAppAttempt attempt1 = app1.getCurrentAppAttempt();
    Assert.assertTrue(((RMAppAttemptImpl) attempt1).mayBeLastAttempt());

    // Restart rm.
    MockRM rm2 = new MockRM(conf, memStore);
    rm2.start();
    ApplicationStateData appState =
        memStore.getState().getApplicationState().get(app1.getApplicationId());
    // re-register the NM
    nm1.setResourceTrackerService(rm2.getResourceTrackerService());
    NMContainerStatus status = Records.newRecord(NMContainerStatus.class);
    status
      .setContainerExitStatus(ContainerExitStatus.KILLED_BY_RESOURCEMANAGER);
    status.setContainerId(attempt1.getMasterContainer().getId());
    status.setContainerState(ContainerState.COMPLETE);
    status.setDiagnostics("");
    nm1.registerNode(Collections.singletonList(status), null);

    rm2.waitForState(attempt1.getAppAttemptId(), RMAppAttemptState.FAILED);
    Assert.assertEquals(ContainerExitStatus.KILLED_BY_RESOURCEMANAGER,
      appState.getAttempt(am1.getApplicationAttemptId())
        .getAMContainerExitStatus());
    // Will automatically start a new AppAttempt in rm2
    rm2.waitForState(app1.getApplicationId(), RMAppState.ACCEPTED);
    MockAM am2 =
        rm2.waitForNewAMToLaunchAndRegister(app1.getApplicationId(), 2, nm1);
    MockRM.finishAMAndVerifyAppState(app1, rm2, nm1, am2);
    RMAppAttempt attempt3 =
        rm2.getRMContext().getRMApps().get(app1.getApplicationId())
          .getCurrentAppAttempt();
    Assert.assertTrue(attempt3.shouldCountTowardsMaxAttemptRetry());
    Assert.assertEquals(ContainerExitStatus.INVALID,
      appState.getAttempt(am2.getApplicationAttemptId())
        .getAMContainerExitStatus());

    rm1.stop();
    rm2.stop();
  }

  @Test (timeout = 120000)
  public void testRMAppAttemptFailuresValidityInterval() throws Exception {
    YarnConfiguration conf = new YarnConfiguration();
    conf.setClass(YarnConfiguration.RM_SCHEDULER, CapacityScheduler.class,
      ResourceScheduler.class);
    conf.setBoolean(YarnConfiguration.RECOVERY_ENABLED, true);
    conf.setBoolean(YarnConfiguration.RM_WORK_PRESERVING_RECOVERY_ENABLED, false);

    conf.set(YarnConfiguration.RM_STORE, MemoryRMStateStore.class.getName());
    // explicitly set max-am-retry count as 2.
    conf.setInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS, 2);
    MemoryRMStateStore memStore = new MemoryRMStateStore();
    memStore.init(conf);

    MockRM rm1 = new MockRM(conf, memStore);
    rm1.start();
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 8000, rm1.getResourceTrackerService());
    nm1.registerNode();

    // set window size to a larger number : 60s
    // we will verify the app should be failed if
    // two continuous attempts failed in 60s.
    RMApp app = rm1.submitApp(200, 60000);
    
    MockAM am = MockRM.launchAM(app, rm1, nm1);
    // Fail current attempt normally
    nm1.nodeHeartbeat(am.getApplicationAttemptId(),
      1, ContainerState.COMPLETE);
    am.waitForState(RMAppAttemptState.FAILED);
    // launch the second attempt
    rm1.waitForState(app.getApplicationId(), RMAppState.ACCEPTED);
    Assert.assertEquals(2, app.getAppAttempts().size());
    Assert.assertTrue(((RMAppAttemptImpl) app.getCurrentAppAttempt())
      .mayBeLastAttempt());
    MockAM am_2 = MockRM.launchAndRegisterAM(app, rm1, nm1);
    am_2.waitForState(RMAppAttemptState.RUNNING);
    nm1.nodeHeartbeat(am_2.getApplicationAttemptId(),
      1, ContainerState.COMPLETE);
    am_2.waitForState(RMAppAttemptState.FAILED);
    // current app should be failed.
    rm1.waitForState(app.getApplicationId(), RMAppState.FAILED);

    ControlledClock clock = new ControlledClock();
    // set window size to 10s
    RMAppImpl app1 = (RMAppImpl)rm1.submitApp(200, 10000);;
    app1.setSystemClock(clock);
    MockAM am1 = MockRM.launchAndRegisterAM(app1, rm1, nm1);

    // Fail attempt1 normally
    nm1.nodeHeartbeat(am1.getApplicationAttemptId(),
      1, ContainerState.COMPLETE);
    am1.waitForState(RMAppAttemptState.FAILED);

    // launch the second attempt
    rm1.waitForState(app1.getApplicationId(), RMAppState.ACCEPTED);
    Assert.assertEquals(2, app1.getAppAttempts().size());

    RMAppAttempt attempt2 = app1.getCurrentAppAttempt();
    Assert.assertTrue(((RMAppAttemptImpl) attempt2).mayBeLastAttempt());
    MockAM am2 = MockRM.launchAndRegisterAM(app1, rm1, nm1);
    am2.waitForState(RMAppAttemptState.RUNNING);

    // wait for 10 seconds
    clock.setTime(System.currentTimeMillis() + 10*1000);
    // Fail attempt2 normally
    nm1.nodeHeartbeat(am2.getApplicationAttemptId(),
      1, ContainerState.COMPLETE);
    am2.waitForState(RMAppAttemptState.FAILED);

    // can launch the third attempt successfully
    rm1.waitForState(app1.getApplicationId(), RMAppState.ACCEPTED);
    Assert.assertEquals(3, app1.getAppAttempts().size());
    RMAppAttempt attempt3 = app1.getCurrentAppAttempt();
    clock.reset();
    MockAM am3 = MockRM.launchAndRegisterAM(app1, rm1, nm1);
    am3.waitForState(RMAppAttemptState.RUNNING);

    // Restart rm.
    @SuppressWarnings("resource")
    MockRM rm2 = new MockRM(conf, memStore);
    rm2.start();
    ApplicationStateData app1State =
        memStore.getState().getApplicationState().get(app1.getApplicationId());
    Assert.assertEquals(1, app1State.getFirstAttemptId());

    // re-register the NM
    nm1.setResourceTrackerService(rm2.getResourceTrackerService());
    NMContainerStatus status = Records.newRecord(NMContainerStatus.class);
    status
      .setContainerExitStatus(ContainerExitStatus.KILLED_BY_RESOURCEMANAGER);
    status.setContainerId(attempt3.getMasterContainer().getId());
    status.setContainerState(ContainerState.COMPLETE);
    status.setDiagnostics("");
    nm1.registerNode(Collections.singletonList(status), null);

    rm2.waitForState(attempt3.getAppAttemptId(), RMAppAttemptState.FAILED);
    Assert.assertEquals(2, app1State.getAttemptCount());

    rm2.waitForState(app1.getApplicationId(), RMAppState.ACCEPTED);

    // Lauch Attempt 4
    MockAM am4 =
        rm2.waitForNewAMToLaunchAndRegister(app1.getApplicationId(), 4, nm1);

    // wait for 10 seconds
    clock.setTime(System.currentTimeMillis() + 10*1000);
    // Fail attempt4 normally
    nm1
      .nodeHeartbeat(am4.getApplicationAttemptId(), 1, ContainerState.COMPLETE);
    am4.waitForState(RMAppAttemptState.FAILED);
    Assert.assertEquals(2, app1State.getAttemptCount());

    // can launch the 5th attempt successfully
    rm2.waitForState(app1.getApplicationId(), RMAppState.ACCEPTED);

    MockAM am5 =
        rm2.waitForNewAMToLaunchAndRegister(app1.getApplicationId(), 5, nm1);
    clock.reset();
    am5.waitForState(RMAppAttemptState.RUNNING);

    // Fail attempt5 normally
    nm1
      .nodeHeartbeat(am5.getApplicationAttemptId(), 1, ContainerState.COMPLETE);
    am5.waitForState(RMAppAttemptState.FAILED);
    Assert.assertEquals(2, app1State.getAttemptCount());

    rm2.waitForState(app1.getApplicationId(), RMAppState.FAILED);
    rm1.stop();
    rm2.stop();
  }

  private boolean isContainerIdInContainerStatus(
      List<ContainerStatus> containerStatuses, ContainerId containerId) {
    for (ContainerStatus status : containerStatuses) {
      if (status.getContainerId().equals(containerId)) {
        return true;
      }
    }
    return false;
  }

  @Test(timeout = 30000)
  public void testAMRestartNotLostContainerCompleteMsg() throws Exception {
    YarnConfiguration conf = new YarnConfiguration();
    conf.setInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS, 2);

    MockRM rm1 = new MockRM(conf);
    rm1.start();
    RMApp app1 =
        rm1.submitApp(200, "name", "user",
            new HashMap<ApplicationAccessType, String>(), false, "default", -1,
            null, "MAPREDUCE", false, true);
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 10240, rm1.getResourceTrackerService());
    nm1.registerNode();

    MockAM am1 = MockRM.launchAndRegisterAM(app1, rm1, nm1);
    allocateContainers(nm1, am1, 1);

    nm1.nodeHeartbeat(
        am1.getApplicationAttemptId(), 2, ContainerState.RUNNING);
    ContainerId containerId2 =
        ContainerId.newContainerId(am1.getApplicationAttemptId(), 2);
    rm1.waitForState(nm1, containerId2, RMContainerState.RUNNING);

    // container complete
    nm1.nodeHeartbeat(
        am1.getApplicationAttemptId(), 2, ContainerState.COMPLETE);
    rm1.waitForState(nm1, containerId2, RMContainerState.COMPLETED);

    // make sure allocate() get complete container,
    // before this msg pass to AM, AM may crash
    while (true) {
      AllocateResponse response = am1.allocate(
          new ArrayList<ResourceRequest>(), new ArrayList<ContainerId>());
      List<ContainerStatus> containerStatuses =
          response.getCompletedContainersStatuses();
      if (isContainerIdInContainerStatus(
          containerStatuses, containerId2) == false) {
        Thread.sleep(100);
        continue;
      }

      // is containerId still in justFinishedContainer?
      containerStatuses =
          app1.getCurrentAppAttempt().getJustFinishedContainers();
      if (isContainerIdInContainerStatus(containerStatuses,
          containerId2)) {
        Assert.fail();
      }
      break;
    }

    // fail the AM by sending CONTAINER_FINISHED event without registering.
    nm1.nodeHeartbeat(
        am1.getApplicationAttemptId(), 1, ContainerState.COMPLETE);
    am1.waitForState(RMAppAttemptState.FAILED);

    // wait for app to start a new attempt.
    rm1.waitForState(app1.getApplicationId(), RMAppState.ACCEPTED);
    // assert this is a new AM.
    ApplicationAttemptId newAttemptId =
        app1.getCurrentAppAttempt().getAppAttemptId();
    Assert.assertFalse(newAttemptId.equals(am1.getApplicationAttemptId()));

    // launch the new AM
    RMAppAttempt attempt2 = app1.getCurrentAppAttempt();
    nm1.nodeHeartbeat(true);
    MockAM am2 = rm1.sendAMLaunched(attempt2.getAppAttemptId());
    am2.registerAppAttempt();
    rm1.waitForState(app1.getApplicationId(), RMAppState.RUNNING);

    // whether new AM could get container complete msg
    AllocateResponse allocateResponse = am2.allocate(
        new ArrayList<ResourceRequest>(), new ArrayList<ContainerId>());
    List<ContainerStatus> containerStatuses =
        allocateResponse.getCompletedContainersStatuses();
    if (isContainerIdInContainerStatus(containerStatuses,
        containerId2) == false) {
      Assert.fail();
    }
    containerStatuses = attempt2.getJustFinishedContainers();
    if (isContainerIdInContainerStatus(containerStatuses, containerId2)) {
      Assert.fail();
    }

    // the second allocate should not get container complete msg
    allocateResponse = am2.allocate(
        new ArrayList<ResourceRequest>(), new ArrayList<ContainerId>());
    containerStatuses =
        allocateResponse.getCompletedContainersStatuses();
    if (isContainerIdInContainerStatus(containerStatuses, containerId2)) {
      Assert.fail();
    }

    rm1.stop();
  }
}
