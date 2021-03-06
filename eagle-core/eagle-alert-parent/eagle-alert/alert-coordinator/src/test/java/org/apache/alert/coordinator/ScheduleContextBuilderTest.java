/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.alert.coordinator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.alert.coordinator.mock.InMemMetadataServiceClient;
import org.apache.eagle.alert.coordination.model.Kafka2TupleMetadata;
import org.apache.eagle.alert.coordination.model.ScheduleState;
import org.apache.eagle.alert.coordination.model.Tuple2StreamMetadata;
import org.apache.eagle.alert.coordination.model.WorkSlot;
import org.apache.eagle.alert.coordination.model.internal.MonitoredStream;
import org.apache.eagle.alert.coordination.model.internal.PolicyAssignment;
import org.apache.eagle.alert.coordination.model.internal.StreamGroup;
import org.apache.eagle.alert.coordination.model.internal.StreamWorkSlotQueue;
import org.apache.eagle.alert.coordination.model.internal.Topology;
import org.apache.eagle.alert.coordinator.IScheduleContext;
import org.apache.eagle.alert.coordinator.model.AlertBoltUsage;
import org.apache.eagle.alert.coordinator.model.TopologyUsage;
import org.apache.eagle.alert.coordinator.provider.ScheduleContextBuilder;
import org.apache.eagle.alert.engine.coordinator.PolicyDefinition;
import org.apache.eagle.alert.engine.coordinator.PolicyDefinition.Definition;
import org.apache.eagle.alert.engine.coordinator.Publishment;
import org.apache.eagle.alert.engine.coordinator.StreamColumn;
import org.apache.eagle.alert.engine.coordinator.StreamColumn.Type;
import org.apache.eagle.alert.engine.coordinator.StreamDefinition;
import org.apache.eagle.alert.engine.coordinator.StreamPartition;
import org.apache.eagle.alert.engine.coordinator.StreamSortSpec;
import org.junit.Assert;
import org.junit.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * @since May 5, 2016
 */
public class ScheduleContextBuilderTest {

    Config config = ConfigFactory.load().getConfig("coordinator");

    @Test
    public void test() {
        InMemMetadataServiceClient client = getSampleMetadataService();

        ScheduleContextBuilder builder = new ScheduleContextBuilder(config, client);

        IScheduleContext context = builder.buildContext();

        // assert topology usage
        Map<String, TopologyUsage> usages = context.getTopologyUsages();
        Assert.assertEquals(1, usages.get(TOPO1).getMonitoredStream().size());
        Assert.assertTrue(usages.get(TOPO1).getPolicies().contains(TEST_POLICY_1));

        String alertBolt0 = TOPO1 + "-alert-" + "0";
        String alertBolt1 = TOPO1 + "-alert-" + "1";
        String alertBolt2 = TOPO1 + "-alert-" + "2";
        for (AlertBoltUsage u : usages.get(TOPO1).getAlertUsages().values()) {
            if (u.getBoltId().equals(alertBolt0) || u.getBoltId().equals(alertBolt1)
                || u.getBoltId().equals(alertBolt2)) {
                Assert.assertEquals(1, u.getPolicies().size());
                Assert.assertTrue(u.getPolicies().contains(TEST_POLICY_1));
                Assert.assertEquals(1, u.getPartitions().size());
                Assert.assertEquals(1, u.getReferQueues().size());
            }
        }
    }

    @Test
    public void test_remove_policy() {
        InMemMetadataServiceClient client = getSampleMetadataService();
        ScheduleContextBuilder builder = new ScheduleContextBuilder(config, client);

        PolicyAssignment assignment1 = client.getVersionedSpec().getAssignments().get(0);

        IScheduleContext context = builder.buildContext();
        Assert.assertTrue(context.getPolicyAssignments().containsKey(TEST_POLICY_1));
        StreamWorkSlotQueue queue = SchedulerTest.getQueue(context, assignment1.getQueueId()).getRight();

        client.removePolicy(0);
        context = builder.buildContext();
        Assert.assertFalse(context.getPolicyAssignments().containsKey(TEST_POLICY_1));

        WorkSlot slot = queue.getWorkingSlots().get(0);
        Set<String> topoPolicies = context.getTopologyUsages().get(slot.topologyName).getPolicies();
        Assert.assertFalse(topoPolicies.contains(TEST_DATASOURCE_1));
        Assert.assertEquals(0, topoPolicies.size());
    }

    @Test
    public void test_changed_policy_partition() {
        InMemMetadataServiceClient client = getSampleMetadataService();
        ScheduleContextBuilder builder = new ScheduleContextBuilder(config, client);
        PolicyAssignment assignment1 = client.getVersionedSpec().getAssignments().get(0);

        IScheduleContext context = builder.buildContext();
        Assert.assertTrue(context.getPolicyAssignments().containsKey(TEST_POLICY_1));

        StreamWorkSlotQueue queue = SchedulerTest.getQueue(context, assignment1.getQueueId()).getRight();

        PolicyDefinition pd1 = client.listPolicies().get(0);
        // add a new group by column : need to replace the partiton spec, to
        // avoid reference same object in
        // on jvm (no serialization and deserialization)
        StreamPartition par = new StreamPartition(pd1.getPartitionSpec().get(0));
        par.getColumns().add("s1");
        pd1.getPartitionSpec().clear();
        pd1.getPartitionSpec().add(par);

        context = builder.buildContext();

        // assert the policy assignment is removed
        Assert.assertFalse(context.getPolicyAssignments().containsKey(TEST_POLICY_1));
        // assert the monitored stream is removed as no policy on it now.
        Assert.assertEquals(0, context.getMonitoredStreams().size());
        // assert the topology usage doesn't contain policy
        WorkSlot slot = queue.getWorkingSlots().get(0);
        TopologyUsage topologyUsage = context.getTopologyUsages().get(slot.topologyName);
        Set<String> topoPolicies = topologyUsage.getPolicies();
        Assert.assertFalse(topoPolicies.contains(TEST_DATASOURCE_1));
        Assert.assertEquals(0, topoPolicies.size());
        // assert the topology usage doesn't contain the monitored stream
        Assert.assertEquals(0, topologyUsage.getMonitoredStream().size());
        // assert the alert bolt usage doesn't have the queue reference
        Assert.assertEquals(0, topologyUsage.getAlertBoltUsage(slot.getBoltId()).getReferQueues().size());
    }

    @Test
    public void test_changed_policy_parallelism() {
        InMemMetadataServiceClient client = getSampleMetadataService();
        ScheduleContextBuilder builder = new ScheduleContextBuilder(config, client);
        PolicyAssignment assignment1 = client.getVersionedSpec().getAssignments().get(0);

        IScheduleContext context = builder.buildContext();
        Assert.assertTrue(context.getPolicyAssignments().containsKey(TEST_POLICY_1));

        StreamWorkSlotQueue queue = SchedulerTest.getQueue(context, assignment1.getQueueId()).getRight();

        PolicyDefinition pd1 = client.listPolicies().get(0);
        pd1.setParallelismHint(4); // default queue is 5 , change to smaller, same like change bigger

        context = builder.buildContext();
        Assert.assertFalse(context.getPolicyAssignments().values().iterator().hasNext());
        //PolicyAssignment assignmentNew = context.getPolicyAssignments().values().iterator().next();
        //StreamWorkSlotQueue queueNew = SchedulerTest.getQueue(context, assignmentNew.getQueueId()).getRight();
        //Assert.assertNotNull(queueNew);
        // just to make sure queueNew is present
        //Assert.assertEquals(queue.getQueueId(), queueNew.getQueueId());

        // default queue is 5 , change to bigger 6, policy assignment removed
        pd1.setParallelismHint(queue.getQueueSize() + 1);
        context = builder.buildContext();

        Assert.assertFalse(context.getPolicyAssignments().values().iterator().hasNext());
    }

    @Test
    public void test_changed_policy_definition() {
        InMemMetadataServiceClient client = getSampleMetadataService();
        ScheduleContextBuilder builder = new ScheduleContextBuilder(config, client);
        PolicyAssignment assignment1 = client.getVersionedSpec().getAssignments().get(0);

        IScheduleContext context = builder.buildContext();
        Assert.assertTrue(context.getPolicyAssignments().containsKey(TEST_POLICY_1));

        StreamWorkSlotQueue queue = SchedulerTest.getQueue(context, assignment1.getQueueId()).getRight();

        PolicyDefinition pd1 = client.listPolicies().get(0);
        pd1.getDefinition().value = "define.. new...";

        context = builder.buildContext();
        PolicyAssignment assignmentNew = context.getPolicyAssignments().values().iterator().next();
        StreamWorkSlotQueue queueNew = SchedulerTest.getQueue(context, assignmentNew.getQueueId()).getRight();
        Assert.assertNotNull(queueNew);
        // just to make sure queueNew is present
        Assert.assertEquals(queue.getQueueId(), queueNew.getQueueId());
    }

    @Test
    public void test_stream_noalert_policies_generation() throws Exception {
        InMemMetadataServiceClient client = getSampleMetadataServiceWithNodataAlert();

        ScheduleContextBuilder builder = new ScheduleContextBuilder(config, client);
        IScheduleContext context = builder.buildContext();

        PolicyDefinition policyDefinition = null;
        PolicyDefinition aggrPolicyDefinition = null;
        for (Entry<String, PolicyDefinition> entry : context.getPolicies().entrySet()) {
            if (entry.getKey().endsWith("_nodata_alert")) {
                policyDefinition = entry.getValue();
                continue;
            }
            if (entry.getKey().endsWith("_aggregation_stream_policy")) {
                aggrPolicyDefinition = entry.getValue();
                continue;
            }
        }
        Assert.assertEquals(3, context.getPolicies().size());

        Assert.assertNotNull(policyDefinition);
        Assert.assertEquals("nodataalert", policyDefinition.getDefinition().getType());
        Assert.assertEquals("PT5S,dynamic,1," + COL1, policyDefinition.getDefinition().getValue());

        Assert.assertNotNull(aggrPolicyDefinition);
        Assert.assertEquals("siddhi", aggrPolicyDefinition.getDefinition().getType());

        Kafka2TupleMetadata datasource = null;
        for (Entry<String, Kafka2TupleMetadata> entry : context.getDataSourceMetadata().entrySet()) {
            if ("nodata_alert_aggregation_ds".equals(entry.getKey())) {
                datasource = entry.getValue();
                break;
            }
        }
        Assert.assertNotNull(datasource);

        String publishmentName = policyDefinition.getName() + "_publish";
        Publishment publishment = null;
        for (Entry<String, Publishment> entry : context.getPublishments().entrySet()) {
            if (publishmentName.equals(entry.getKey())) {
                publishment = entry.getValue();
                break;
            }
        }
        Assert.assertNotNull(publishment);
    }

    @Test
    public void test_renamed_topologies() {
        InMemMetadataServiceClient client = getSampleMetadataService();
        ScheduleContextBuilder builder = new ScheduleContextBuilder(config, client);

        IScheduleContext context = builder.buildContext();
        Assert.assertTrue(context.getPolicyAssignments().containsKey(TEST_POLICY_1));

        Topology t = client.listTopologies().get(0);
        t.setName("newName");

        context = builder.buildContext();
        Assert.assertFalse(context.getPolicyAssignments().containsKey(TEST_POLICY_1));
    }

    private static final String TOPO1 = "topo1";
    private static final String V1 = "v1";
    private static final String COL1 = "col1";
    private static final String OUT_STREAM1 = "out-stream1";
    private static final String TEST_POLICY_1 = "test-policy-1";
    private static final String TEST_STREAM_DEF_1 = "testStreamDef";
    private static final String TEST_DATASOURCE_1 = "test-datasource-1";
    private static StreamPartition par;
    private static String queueId;
    private static StreamGroup streamGroup;

    public static InMemMetadataServiceClient getSampleMetadataService() {
        InMemMetadataServiceClient client = new InMemMetadataServiceClient();
        client.addTopology(createSampleTopology());
        client.addDataSource(createKafka2TupleMetadata());
        client.addPolicy(createPolicy());
        client.addPublishment(createPublishment());
        client.addStreamDefinition(createStreamDefinition());
        client.addScheduleState(createScheduleState());
        return client;
    }

    public static InMemMetadataServiceClient getSampleMetadataServiceWithNodataAlert() {
        InMemMetadataServiceClient client = new InMemMetadataServiceClient();
        client.addTopology(createSampleTopology());
        client.addDataSource(createKafka2TupleMetadata());
        client.addPolicy(createPolicy());
        client.addPublishment(createPublishment());
        client.addStreamDefinition(createStreamDefinitionWithNodataAlert());
        client.addScheduleState(createScheduleState());
        return client;
    }

    private static StreamDefinition createStreamDefinitionWithNodataAlert() {
        StreamDefinition def = new StreamDefinition();
        def.setStreamId(TEST_STREAM_DEF_1);
        def.setDataSource(TEST_DATASOURCE_1);

        StreamColumn col = new StreamColumn();
        col.setName(COL1);
        col.setRequired(true);
        col.setType(Type.STRING);
        col.setNodataExpression("PT5S,dynamic,1," + COL1);
        def.getColumns().add(col);

        return def;
    }


    private static ScheduleState createScheduleState() {
        ScheduleState ss = new ScheduleState();
        ss.setVersion(V1);

        ss.getMonitoredStreams().add(createMonitoredStream());
        ss.getAssignments().add(createAssignment());

        return ss;
    }

    private static MonitoredStream createMonitoredStream() {
        MonitoredStream ms = new MonitoredStream(streamGroup);
        ms.setVersion(V1);

        List<WorkSlot> slots = new ArrayList<WorkSlot>();
        WorkSlot slot0 = new WorkSlot(TOPO1, TOPO1 + "-alert-" + 0);
        WorkSlot slot1 = new WorkSlot(TOPO1, TOPO1 + "-alert-" + 1);
        WorkSlot slot2 = new WorkSlot(TOPO1, TOPO1 + "-alert-" + 2);
        WorkSlot slot3 = new WorkSlot(TOPO1, TOPO1 + "-alert-" + 3);
        WorkSlot slot4 = new WorkSlot(TOPO1, TOPO1 + "-alert-" + 4);
        WorkSlot slot5 = new WorkSlot(TOPO1, TOPO1 + "-alert-" + 5);
        slots.add(slot0);
        slots.add(slot1);
        slots.add(slot2);
        slots.add(slot3);
        slots.add(slot4);
        //slots.add(slot5);

        StreamWorkSlotQueue q = new StreamWorkSlotQueue(streamGroup, false, new HashMap<>(), slots);
        ms.addQueues(q);
        queueId = q.getQueueId();
        return ms;
    }

    private static PolicyAssignment createAssignment() {
        PolicyAssignment pa = new PolicyAssignment(TEST_POLICY_1, queueId);
        return pa;
    }

    private static PolicyDefinition createPolicy() {
        PolicyDefinition def = new PolicyDefinition();
        def.setName(TEST_POLICY_1);
        def.setInputStreams(Arrays.asList(TEST_STREAM_DEF_1));
        def.setOutputStreams(Arrays.asList(OUT_STREAM1));
        def.setParallelismHint(5);
        def.setDefinition(new Definition());

        streamGroup = new StreamGroup();
        par = new StreamPartition();
        par.setStreamId(TEST_STREAM_DEF_1);
        par.getColumns().add(COL1);
        StreamSortSpec sortSpec = new StreamSortSpec();
//        sortSpec.setColumn("col1");
        sortSpec.setWindowMargin(3);
        sortSpec.setWindowPeriod("PT1M");

        par.setSortSpec(sortSpec);
        streamGroup.addStreamPartition(par);

        List<StreamPartition> lists = new ArrayList<StreamPartition>();
        lists.add(par);
        def.setPartitionSpec(lists);
        return def;
    }

    private static StreamDefinition createStreamDefinition() {
        StreamDefinition def = new StreamDefinition();
        def.setStreamId(TEST_STREAM_DEF_1);
        def.setDataSource(TEST_DATASOURCE_1);

        StreamColumn col = new StreamColumn();
        col.setName(COL1);
        col.setRequired(true);
        col.setType(Type.STRING);
        def.getColumns().add(col);

        return def;
    }

    private static Publishment createPublishment() {
        Publishment pub = new Publishment();
        pub.setType("KAFKA");
        pub.setName("test-stream-output");
        pub.setPolicyIds(Arrays.asList(TEST_POLICY_1));
        return pub;
    }

    private static Kafka2TupleMetadata createKafka2TupleMetadata() {
        Kafka2TupleMetadata ktm = new Kafka2TupleMetadata();
        ktm.setName(TEST_DATASOURCE_1);
        ktm.setSchemeCls("SchemeClass");
        ktm.setTopic("tupleTopic");
        ktm.setType("KAFKA");
        ktm.setCodec(new Tuple2StreamMetadata());
        return ktm;
    }

    private static Topology createSampleTopology() {
        Topology t = new Topology(TOPO1, 3, 10);
        for (int i = 0; i < t.getNumOfGroupBolt(); i++) {
            t.getGroupNodeIds().add(t.getName() + "-grp-" + i);
        }
        for (int i = 0; i < t.getNumOfAlertBolt(); i++) {
            t.getAlertBoltIds().add(t.getName() + "-alert-" + i);
        }
        return t;
    }

}
