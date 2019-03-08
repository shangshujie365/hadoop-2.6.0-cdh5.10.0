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

package org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Evolving;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.AccessControlList;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.QueueACL;
import org.apache.hadoop.yarn.api.records.QueueInfo;
import org.apache.hadoop.yarn.api.records.QueueState;
import org.apache.hadoop.yarn.api.records.QueueUserACLInfo;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.nodelabels.CommonNodeLabelsManager;
import org.apache.hadoop.yarn.security.AccessType;
import org.apache.hadoop.yarn.server.resourcemanager.nodelabels.RMNodeLabelsManager;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerState;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ActiveUsersManager;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.NodeType;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerApplicationAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerUtils;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.common.fica.FiCaSchedulerApp;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.common.fica.FiCaSchedulerNode;
import org.apache.hadoop.yarn.util.resource.Resources;

import com.google.common.collect.Sets;

@Private
@Evolving
public class ParentQueue extends AbstractCSQueue {

  private static final Log LOG = LogFactory.getLog(ParentQueue.class);

  protected final Set<CSQueue> childQueues;  
  private final boolean rootQueue;
  final Comparator<CSQueue> queueComparator;
  volatile int numApplications;

  private final RecordFactory recordFactory = 
    RecordFactoryProvider.getRecordFactory(null);

  public ParentQueue(CapacitySchedulerContext cs, 
      String queueName, CSQueue parent, CSQueue old) throws IOException {
    super(cs, queueName, parent, old);
    
    this.queueComparator = cs.getQueueComparator();

    this.rootQueue = (parent == null);

    float rawCapacity = cs.getConfiguration().getCapacity(getQueuePath());

    if (rootQueue &&
        (rawCapacity != CapacitySchedulerConfiguration.MAXIMUM_CAPACITY_VALUE)) {
      throw new IllegalArgumentException("Illegal " +
          "capacity of " + rawCapacity + " for queue " + queueName +
          ". Must be " + CapacitySchedulerConfiguration.MAXIMUM_CAPACITY_VALUE);
    }

    float capacity = (float) rawCapacity / 100;
    float parentAbsoluteCapacity = 
      (rootQueue) ? 1.0f : parent.getAbsoluteCapacity();
    float absoluteCapacity = parentAbsoluteCapacity * capacity; 

    float  maximumCapacity =
      (float) cs.getConfiguration().getMaximumCapacity(getQueuePath()) / 100;
    float absoluteMaxCapacity = 
          CSQueueUtils.computeAbsoluteMaximumCapacity(maximumCapacity, parent);
    
    QueueState state = cs.getConfiguration().getState(getQueuePath());

    Map<AccessType, AccessControlList> acls = 
      cs.getConfiguration().getAcls(getQueuePath());

    setupQueueConfigs(cs.getClusterResource(), capacity, absoluteCapacity,
        maximumCapacity, absoluteMaxCapacity, state, acls, accessibleLabels,
        defaultLabelExpression, capacitiyByNodeLabels, maxCapacityByNodeLabels, 
        cs.getConfiguration().getReservationContinueLook());
    
    this.childQueues = new TreeSet<CSQueue>(queueComparator);

    LOG.info("Initialized parent-queue " + queueName + 
        " name=" + queueName + 
        ", fullname=" + getQueuePath()); 
  }

  synchronized void setupQueueConfigs(Resource clusterResource, float capacity,
      float absoluteCapacity, float maximumCapacity, float absoluteMaxCapacity,
      QueueState state, Map<AccessType, AccessControlList> acls,
      Set<String> accessibleLabels, String defaultLabelExpression,
      Map<String, Float> nodeLabelCapacities,
      Map<String, Float> maximumCapacitiesByLabel, 
      boolean reservationContinueLooking) throws IOException {
    super.setupQueueConfigs(clusterResource, capacity, absoluteCapacity,
        maximumCapacity, absoluteMaxCapacity, state, acls, accessibleLabels,
        defaultLabelExpression, nodeLabelCapacities, maximumCapacitiesByLabel,
        reservationContinueLooking);
   StringBuilder aclsString = new StringBuilder();
    for (Map.Entry<AccessType, AccessControlList> e : acls.entrySet()) {
      aclsString.append(e.getKey() + ":" + e.getValue().getAclString());
    }

    StringBuilder labelStrBuilder = new StringBuilder(); 
    if (accessibleLabels != null) {
      for (String s : accessibleLabels) {
        labelStrBuilder.append(s);
        labelStrBuilder.append(",");
      }
    }

    LOG.info(queueName +
        ", capacity=" + capacity +
        ", asboluteCapacity=" + absoluteCapacity +
        ", maxCapacity=" + maximumCapacity +
        ", asboluteMaxCapacity=" + absoluteMaxCapacity + 
        ", state=" + state +
        ", acls=" + aclsString + 
        ", labels=" + labelStrBuilder.toString() + "\n" +
        ", reservationsContinueLooking=" + reservationsContinueLooking);
  }

  private static float PRECISION = 0.0005f; // 0.05% precision
  void setChildQueues(Collection<CSQueue> childQueues) {
    // Validate
	 //所有子队列的容量加上应该是100,root 默认100.0f
    float childCapacities = 0;
    for (CSQueue queue : childQueues) {
      childCapacities += queue.getCapacity();
    }
    float delta = Math.abs(1.0f - childCapacities);  // crude way to check
    // allow capacities being set to 0, and enforce child 0 if parent is 0
    if (((capacity > 0) && (delta > PRECISION)) || 
        ((capacity == 0) && (childCapacities > 0))) {
      throw new IllegalArgumentException("Illegal" +
      		" capacity of " + childCapacities + 
      		" for children of queue " + queueName);
    }
    // check label capacities
    //所有子队列标签的容量加起来应试是100，如果子队列指定了容量，而父队列没有指定容量或子队列加起来不等于父队列是要报错的
    //root必须指定所有标签为容量100,各个子队列再各自指定容量!
    for (String nodeLabel : labelManager.getClusterNodeLabels()) {
      float capacityByLabel = getCapacityByNodeLabel(nodeLabel);
      // check children's labels
      float sum = 0;
      for (CSQueue queue : childQueues) {
        sum += queue.getCapacityByNodeLabel(nodeLabel);
      }
      if ((capacityByLabel > 0 && Math.abs(1.0f - sum) > PRECISION)
          || (capacityByLabel == 0) && (sum > 0)) {
        throw new IllegalArgumentException("Illegal" + " capacity of "
            + sum + " for children of queue " + queueName
            + " for label=" + nodeLabel);
      }
    }
    
    this.childQueues.clear();
    this.childQueues.addAll(childQueues);
    if (LOG.isDebugEnabled()) {
      LOG.debug("setChildQueues: " + getChildQueuesToPrint());
    }
  }

  @Override
  public String getQueuePath() {
    String parentPath = ((parent == null) ? "" : (parent.getQueuePath() + "."));
    return parentPath + getQueueName();
  }

  @Override
  public synchronized QueueInfo getQueueInfo( 
      boolean includeChildQueues, boolean recursive) {
    QueueInfo queueInfo = getQueueInfo();

    List<QueueInfo> childQueuesInfo = new ArrayList<QueueInfo>();
    if (includeChildQueues) {
      for (CSQueue child : childQueues) {
        // Get queue information recursively?
        childQueuesInfo.add(
            child.getQueueInfo(recursive, recursive));
      }
    }
    queueInfo.setChildQueues(childQueuesInfo);
    
    return queueInfo;
  }

  private synchronized QueueUserACLInfo getUserAclInfo(
      UserGroupInformation user) {
    QueueUserACLInfo userAclInfo = 
      recordFactory.newRecordInstance(QueueUserACLInfo.class);
    List<QueueACL> operations = new ArrayList<QueueACL>();
    for (QueueACL operation : QueueACL.values()) {
      if (hasAccess(operation, user)) {
        operations.add(operation);
      } 
    }

    userAclInfo.setQueueName(getQueueName());
    userAclInfo.setUserAcls(operations);
    return userAclInfo;
  }
  
  @Override
  public synchronized List<QueueUserACLInfo> getQueueUserAclInfo(
      UserGroupInformation user) {
    List<QueueUserACLInfo> userAcls = new ArrayList<QueueUserACLInfo>();
    
    // Add parent queue acls
    userAcls.add(getUserAclInfo(user));
    
    // Add children queue acls
    for (CSQueue child : childQueues) {
      userAcls.addAll(child.getQueueUserAclInfo(user));
    }
 
    return userAcls;
  }

  public String toString() {
    return queueName + ": " +
        "numChildQueue= " + childQueues.size() + ", " + 
        "capacity=" + capacity + ", " +  
        "absoluteCapacity=" + absoluteCapacity + ", " +
        "usedResources=" + usedResources + 
        "usedCapacity=" + getUsedCapacity() + ", " + 
        "numApps=" + getNumApplications() + ", " + 
        "numContainers=" + getNumContainers();
  }
  
  @Override
  public synchronized void reinitialize(
      CSQueue newlyParsedQueue, Resource clusterResource)
  throws IOException {
    // Sanity check
    if (!(newlyParsedQueue instanceof ParentQueue) ||
        !newlyParsedQueue.getQueuePath().equals(getQueuePath())) {
      throw new IOException("Trying to reinitialize " + getQueuePath() +
          " from " + newlyParsedQueue.getQueuePath());
    }

    ParentQueue newlyParsedParentQueue = (ParentQueue)newlyParsedQueue;

    // Set new configs
    setupQueueConfigs(clusterResource,
        newlyParsedParentQueue.capacity, 
        newlyParsedParentQueue.absoluteCapacity,
        newlyParsedParentQueue.maximumCapacity, 
        newlyParsedParentQueue.absoluteMaxCapacity,
        newlyParsedParentQueue.state, 
        newlyParsedParentQueue.acls,
        newlyParsedParentQueue.accessibleLabels,
        newlyParsedParentQueue.defaultLabelExpression,
        newlyParsedParentQueue.capacitiyByNodeLabels,
        newlyParsedParentQueue.maxCapacityByNodeLabels,
        newlyParsedParentQueue.reservationsContinueLooking);

    // Re-configure existing child queues and add new ones
    // The CS has already checked to ensure all existing child queues are present!
    Map<String, CSQueue> currentChildQueues = getQueues(childQueues);
    Map<String, CSQueue> newChildQueues = 
        getQueues(newlyParsedParentQueue.childQueues);
    for (Map.Entry<String, CSQueue> e : newChildQueues.entrySet()) {
      String newChildQueueName = e.getKey();
      CSQueue newChildQueue = e.getValue();

      CSQueue childQueue = currentChildQueues.get(newChildQueueName);
      
      // Check if the child-queue already exists
      if (childQueue != null) {
        // Re-init existing child queues
        childQueue.reinitialize(newChildQueue, clusterResource);
        LOG.info(getQueueName() + ": re-configured queue: " + childQueue);
      } else {
        // New child queue, do not re-init
        
        // Set parent to 'this'
        newChildQueue.setParent(this);
        
        // Save in list of current child queues
        currentChildQueues.put(newChildQueueName, newChildQueue);
        
        LOG.info(getQueueName() + ": added new child queue: " + newChildQueue);
      }
    }

    // Re-sort all queues
    childQueues.clear();
    childQueues.addAll(currentChildQueues.values());
  }

  Map<String, CSQueue> getQueues(Set<CSQueue> queues) {
    Map<String, CSQueue> queuesMap = new HashMap<String, CSQueue>();
    for (CSQueue queue : queues) {
      queuesMap.put(queue.getQueueName(), queue);
    }
    return queuesMap;
  }

  @Override
  public void submitApplication(ApplicationId applicationId, String user,
      String queue) throws AccessControlException {
    
    synchronized (this) {
      // Sanity check
      if (queue.equals(queueName)) {
        throw new AccessControlException("Cannot submit application " +
            "to non-leaf queue: " + queueName);
      }
      
      if (state != QueueState.RUNNING) {
        throw new AccessControlException("Queue " + getQueuePath() +
            " is STOPPED. Cannot accept submission of application: " +
            applicationId);
      }

      addApplication(applicationId, user);
    }
    
    // Inform the parent queue
    if (parent != null) {
      try {
        parent.submitApplication(applicationId, user, queue);
      } catch (AccessControlException ace) {
        LOG.info("Failed to submit application to parent-queue: " + 
            parent.getQueuePath(), ace);
        removeApplication(applicationId, user);
        throw ace;
      }
    }
  }


  @Override
  public void submitApplicationAttempt(FiCaSchedulerApp application,
      String userName) {
    // submit attempt logic.
  }

  @Override
  public void finishApplicationAttempt(FiCaSchedulerApp application,
      String queue) {
    // finish attempt logic.
  }

  private synchronized void addApplication(ApplicationId applicationId,
      String user) {

    ++numApplications;

    LOG.info("Application added -" +
        " appId: " + applicationId + 
        " user: " + user + 
        " leaf-queue of parent: " + getQueueName() + 
        " #applications: " + getNumApplications());
  }
  
  @Override
  public void finishApplication(ApplicationId application, String user) {
    
    synchronized (this) {
      removeApplication(application, user);
    }
    
    // Inform the parent queue
    if (parent != null) {
      parent.finishApplication(application, user);
    }
  }

  private synchronized void removeApplication(ApplicationId applicationId, 
      String user) {
    
    --numApplications;

    LOG.info("Application removed -" +
        " appId: " + applicationId + 
        " user: " + user + 
        " leaf-queue of parent: " + getQueueName() + 
        " #applications: " + getNumApplications());
  }

  @Override
  public synchronized CSAssignment assignContainers(
      Resource clusterResource, FiCaSchedulerNode node, boolean needToUnreserve) {
    CSAssignment assignment = 
        new CSAssignment(Resources.createResource(0, 0), NodeType.NODE_LOCAL);
    
    // if our queue cannot access this node, just return
    if (!SchedulerUtils.checkQueueAccessToNode(accessibleLabels,
        labelManager.getLabelsOnNode(node.getNodeID()))) {
      return assignment;
    }
    //如果节点的资源充足，循环向此节点分配任务，根队列才有这个权限
    while (canAssign(clusterResource, node)) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Trying to assign containers to child-queue of "
          + getQueueName());
      }
      
      boolean localNeedToUnreserve = false;
      Set<String> nodeLabels = labelManager.getLabelsOnNode(node.getNodeID()); 
      
      // Are we over maximum-capacity for this queue?
      if (!canAssignToThisQueue(clusterResource, nodeLabels)) {
        // check to see if we could if we unreserve first
        localNeedToUnreserve = assignToQueueIfUnreserve(clusterResource);
        if (!localNeedToUnreserve) {
          break;
        }
      }
      
      // Schedule
      CSAssignment assignedToChild = 
          assignContainersToChildQueues(clusterResource, node, localNeedToUnreserve | needToUnreserve);
      assignment.setType(assignedToChild.getType());
      
      // Done if no child-queue assigned anything
      if (Resources.greaterThan(
              resourceCalculator, clusterResource, 
              assignedToChild.getResource(), Resources.none())) {
        // Track resource utilization for the parent-queue
        super.allocateResource(clusterResource, assignedToChild.getResource(),
            nodeLabels);
        
        // Track resource utilization in this pass of the scheduler
        Resources.addTo(assignment.getResource(), assignedToChild.getResource());
        
        LOG.info("assignedContainer" +
            " queue=" + getQueueName() + 
            " usedCapacity=" + getUsedCapacity() +
            " absoluteUsedCapacity=" + getAbsoluteUsedCapacity() +
            " used=" + usedResources + 
            " cluster=" + clusterResource);

      } else {
        break;//子队列分配失败，直接退出
      }

      if (LOG.isDebugEnabled()) {
        LOG.debug("ParentQ=" + getQueueName()
          + " assignedSoFarInThisIteration=" + assignment.getResource()
          + " usedCapacity=" + getUsedCapacity()
          + " absoluteUsedCapacity=" + getAbsoluteUsedCapacity());
      }

      // Do not assign more than one container if this isn't the root queue
      // or if we've already assigned an off-switch container
      // 不管分配成功或失败，只有根队列才有机会循环分配容器，其它父队列不可以循环调度
      //这个目的还是为了让每一个队列都有机会调度容器。
      if (!rootQueue || assignment.getType() == NodeType.OFF_SWITCH) {
        if (LOG.isDebugEnabled()) {
          if (rootQueue && assignment.getType() == NodeType.OFF_SWITCH) {
            LOG.debug("Not assigning more than one off-switch container," +
                " assignments so far: " + assignment);
          }
        }
        break;
      }
    } 
    
    return assignment;
  }
 /**
  * 判断当前队列标签（队列可访问标签与节点标签交集为空就判断空标签）使用资源是否达到最大容量上限
  * @author fulaihua 2018年6月20日 下午3:38:05
  * @param clusterResource
  * @param nodeLabels
  * @return
  */
  private synchronized boolean canAssignToThisQueue(Resource clusterResource,
      Set<String> nodeLabels) {
    Set<String> labelCanAccess =
        new HashSet<String>(
            accessibleLabels.contains(CommonNodeLabelsManager.ANY) ? nodeLabels
                : Sets.intersection(accessibleLabels, nodeLabels));
    if (nodeLabels.isEmpty()) {
      // Any queue can always access any node without label
      labelCanAccess.add(RMNodeLabelsManager.NO_LABEL);
    }
    //节点上任何一个标签分配没有超过限制那么可以在此节点分配资源
    boolean canAssign = true;
    for (String label : labelCanAccess) {
      if (!usedResourcesByNodeLabels.containsKey(label)) {
        usedResourcesByNodeLabels.put(label, Resources.createResource(0));
      }
      float currentAbsoluteLabelUsedCapacity =
          Resources.divide(resourceCalculator, clusterResource,
              usedResourcesByNodeLabels.get(label),
              labelManager.getResourceByLabel(label, clusterResource));
      // if any of the label doesn't beyond limit, we can allocate on this node
      if (currentAbsoluteLabelUsedCapacity >= 
            getAbsoluteMaximumCapacityByNodeLabel(label)) {
        if (LOG.isDebugEnabled()) {
          LOG.debug(getQueueName() + " used=" + usedResources
              + " current-capacity (" + usedResourcesByNodeLabels.get(label) + ") "
              + " >= max-capacity ("
              + labelManager.getResourceByLabel(label, clusterResource) + ")");
        }
        canAssign = false;
        break;
      }
    }
    
    return canAssign;
  }

  
  private synchronized boolean assignToQueueIfUnreserve(Resource clusterResource) {
    if (this.reservationsContinueLooking) {      
      // check to see if we could potentially use this node instead of a reserved
      // node

      Resource reservedResources = Resources.createResource(getMetrics()
          .getReservedMB(), getMetrics().getReservedVirtualCores());
      float capacityWithoutReservedCapacity = Resources.divide(
          resourceCalculator, clusterResource,
          Resources.subtract(usedResources, reservedResources),
          clusterResource);

      if (capacityWithoutReservedCapacity <= absoluteMaxCapacity) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("parent: try to use reserved: " + getQueueName()
            + " usedResources: " + usedResources.getMemory()
            + " clusterResources: " + clusterResource.getMemory()
            + " reservedResources: " + reservedResources.getMemory()
            + " currentCapacity " + ((float) usedResources.getMemory())
            / clusterResource.getMemory()
            + " potentialNewWithoutReservedCapacity: "
            + capacityWithoutReservedCapacity + " ( " + " max-capacity: "
            + absoluteMaxCapacity + ")");
        }
        // we could potentially use this node instead of reserved node
        return true;
      }
    }
    return false;
   }

  /**
   * 判断当前节点可获得资源是否大于最小可分配资源
   * @author fulaihua 2018年6月15日 下午4:43:27
   * @param clusterResource
   * @param node
   * @return
   */
  private boolean canAssign(Resource clusterResource, FiCaSchedulerNode node) {
    return (node.getReservedContainer() == null) && 
        Resources.greaterThanOrEqual(resourceCalculator, clusterResource, 
            node.getAvailableResource(), minimumAllocation);
  }
  
  private synchronized CSAssignment assignContainersToChildQueues(Resource cluster, 
      FiCaSchedulerNode node, boolean needToUnreserve) {
    CSAssignment assignment = 
        new CSAssignment(Resources.createResource(0, 0), NodeType.NODE_LOCAL);
    
    printChildQueues();
    //容量使用少的排前面，优先分配!队列之间按使用容量排序！
    // Try to assign to most 'under-served' sub-queue
    for (Iterator<CSQueue> iter=childQueues.iterator(); iter.hasNext();) {
      CSQueue childQueue = iter.next();
      if(LOG.isDebugEnabled()) {
        LOG.debug("Trying to assign to queue: " + childQueue.getQueuePath()
          + " stats: " + childQueue);
      }
      assignment = childQueue.assignContainers(cluster, node, needToUnreserve);
      if(LOG.isDebugEnabled()) {
        LOG.debug("Assigned to queue: " + childQueue.getQueuePath() +
          " stats: " + childQueue + " --> " + 
          assignment.getResource() + ", " + assignment.getType());
      }
      //一旦在某一个队列分配成功，就会退出循环，并对队列重排序
      // If we do assign, remove the queue and re-insert in-order to re-sort
      if (Resources.greaterThan(
              resourceCalculator, cluster, 
              assignment.getResource(), Resources.none())) {
        // Remove and re-insert to sort
        iter.remove();
        LOG.info("Re-sorting assigned queue: " + childQueue.getQueuePath() + 
            " stats: " + childQueue);
        childQueues.add(childQueue);
        if (LOG.isDebugEnabled()) {
          printChildQueues();
        }
        break;
      }
    }
    
    return assignment;
  }

  String getChildQueuesToPrint() {
    StringBuilder sb = new StringBuilder();
    for (CSQueue q : childQueues) {
      sb.append(q.getQueuePath() + 
          "usedCapacity=(" + q.getUsedCapacity() + "), " + 
          " label=("
          + StringUtils.join(q.getAccessibleNodeLabels().iterator(), ",") 
          + ")");
    }
    return sb.toString();
  }

  private void printChildQueues() {
    if (LOG.isDebugEnabled()) {
      LOG.debug("printChildQueues - queue: " + getQueuePath()
        + " child-queues: " + getChildQueuesToPrint());
    }
  }
  
  @Override
  public void completedContainer(Resource clusterResource,
      FiCaSchedulerApp application, FiCaSchedulerNode node, 
      RMContainer rmContainer, ContainerStatus containerStatus, 
      RMContainerEventType event, CSQueue completedChildQueue,
      boolean sortQueues) {
    if (application != null) {
      // Careful! Locking order is important!
      // Book keeping
      synchronized (this) {
        super.releaseResource(clusterResource, rmContainer.getContainer()
            .getResource(), labelManager.getLabelsOnNode(node.getNodeID()));

        LOG.info("completedContainer" +
            " queue=" + getQueueName() + 
            " usedCapacity=" + getUsedCapacity() +
            " absoluteUsedCapacity=" + getAbsoluteUsedCapacity() +
            " used=" + usedResources + 
            " cluster=" + clusterResource);
      }

      // Note that this is using an iterator on the childQueues so this can't be
      // called if already within an iterator for the childQueues. Like  
      // from assignContainersToChildQueues.
      if (sortQueues) {
        // reinsert the updated queue
        for (Iterator<CSQueue> iter=childQueues.iterator(); iter.hasNext();) {
          CSQueue csqueue = iter.next();
          if(csqueue.equals(completedChildQueue))
          {
            iter.remove();
            LOG.info("Re-sorting completed queue: " + csqueue.getQueuePath() + 
                " stats: " + csqueue);
            childQueues.add(csqueue);
            break;
          }
        }
      }
      
      // Inform the parent
      if (parent != null) {
        // complete my parent
        parent.completedContainer(clusterResource, application, 
            node, rmContainer, null, event, this, sortQueues);
      }    
    }
  }

  @Override
  public synchronized void updateClusterResource(Resource clusterResource) {
    // Update all children
    for (CSQueue childQueue : childQueues) {
      childQueue.updateClusterResource(clusterResource);
    }
    
    // Update metrics
    CSQueueUtils.updateQueueStatistics(
        resourceCalculator, this, parent, clusterResource, minimumAllocation);
  }
  
  @Override
  public synchronized List<CSQueue> getChildQueues() {
    return new ArrayList<CSQueue>(childQueues);
  }
  
  @Override
  public void recoverContainer(Resource clusterResource,
      SchedulerApplicationAttempt attempt, RMContainer rmContainer) {
    if (rmContainer.getState().equals(RMContainerState.COMPLETED)) {
      return;
    }
    // Careful! Locking order is important! 
    synchronized (this) {
      super.allocateResource(clusterResource, rmContainer.getContainer()
          .getResource(), labelManager.getLabelsOnNode(rmContainer
          .getContainer().getNodeId()));
    }
    if (parent != null) {
      parent.recoverContainer(clusterResource, attempt, rmContainer);
    }
  }
  
  @Override
  public ActiveUsersManager getActiveUsersManager() {
    // Should never be called since all applications are submitted to LeafQueues
    return null;
  }

  @Override
  public void collectSchedulerApplications(
      Collection<ApplicationAttemptId> apps) {
    for (CSQueue queue : childQueues) {
      queue.collectSchedulerApplications(apps);
    }
  }

  @Override
  public void attachContainer(Resource clusterResource,
      FiCaSchedulerApp application, RMContainer rmContainer) {
    if (application != null) {
      super.allocateResource(clusterResource, rmContainer.getContainer()
          .getResource(), labelManager.getLabelsOnNode(rmContainer
          .getContainer().getNodeId()));
      LOG.info("movedContainer" + " queueMoveIn=" + getQueueName()
          + " usedCapacity=" + getUsedCapacity() + " absoluteUsedCapacity="
          + getAbsoluteUsedCapacity() + " used=" + usedResources + " cluster="
          + clusterResource);
      // Inform the parent
      if (parent != null) {
        parent.attachContainer(clusterResource, application, rmContainer);
      }
    }
  }

  @Override
  public void detachContainer(Resource clusterResource,
      FiCaSchedulerApp application, RMContainer rmContainer) {
    if (application != null) {
      super.releaseResource(clusterResource,
          rmContainer.getContainer().getResource(),
          labelManager.getLabelsOnNode(rmContainer.getContainer().getNodeId()));
      LOG.info("movedContainer" + " queueMoveOut=" + getQueueName()
          + " usedCapacity=" + getUsedCapacity() + " absoluteUsedCapacity="
          + getAbsoluteUsedCapacity() + " used=" + usedResources + " cluster="
          + clusterResource);
      // Inform the parent
      if (parent != null) {
        parent.detachContainer(clusterResource, application, rmContainer);
      }
    }
  }

  @Override
  public float getAbsActualCapacity() {
    // for now, simply return actual capacity = guaranteed capacity for parent
    // queue
    return absoluteCapacity;
  }
  
  public synchronized int getNumApplications() {
    return numApplications;
  }
}
