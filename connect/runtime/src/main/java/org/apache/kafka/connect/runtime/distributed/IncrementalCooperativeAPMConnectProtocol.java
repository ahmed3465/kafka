/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.runtime.distributed;

import org.apache.kafka.common.protocol.types.Schema;
import org.apache.kafka.common.protocol.types.SchemaException;
import org.apache.kafka.common.protocol.types.Struct;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.apache.kafka.common.message.JoinGroupRequestData.JoinGroupRequestProtocol;
import static org.apache.kafka.common.message.JoinGroupRequestData.JoinGroupRequestProtocolCollection;
import static org.apache.kafka.connect.runtime.distributed.ConnectProtocol.CONFIG_OFFSET_KEY_NAME;
import static org.apache.kafka.connect.runtime.distributed.ConnectProtocol.CONFIG_STATE_V0;
import static org.apache.kafka.connect.runtime.distributed.ConnectProtocol.CONNECTOR_ASSIGNMENT_V0;
import static org.apache.kafka.connect.runtime.distributed.ConnectProtocol.CONNECT_PROTOCOL_HEADER_SCHEMA;
import static org.apache.kafka.connect.runtime.distributed.ConnectProtocol.CONNECT_PROTOCOL_V0;
import static org.apache.kafka.connect.runtime.distributed.ConnectProtocol.URL_KEY_NAME;
import static org.apache.kafka.connect.runtime.distributed.ConnectProtocol.VERSION_KEY_NAME;
import static org.apache.kafka.connect.runtime.distributed.IncrementalCooperativeConnectProtocol.ALLOCATION_V1;
import static org.apache.kafka.connect.runtime.distributed.IncrementalCooperativeConnectProtocol.ASSIGNMENT_V1;
import static org.apache.kafka.connect.runtime.distributed.ConnectProtocolCompatibility.COMPATIBLE;
import static org.apache.kafka.connect.runtime.distributed.ConnectProtocolCompatibility.APM_COMPATIBLE;
import static org.apache.kafka.connect.runtime.distributed.ConnectProtocolCompatibility.EAGER;
import static org.apache.kafka.connect.runtime.distributed.ConnectProtocolCompatibility.SESSIONED;
import static org.apache.kafka.connect.runtime.distributed.ConnectProtocolCompatibility.APM_SESSIONED;


/**
 * This class implements a group protocol for Kafka Connect workers that support incremental and
 * cooperative rebalancing of connectors and tasks (APM version as well as normal one).
 * It includes the format of worker state used when joining the group and distributing assignments, and the format of
 * assignments of connectors
 * and tasks to workers.
 */
public class IncrementalCooperativeAPMConnectProtocol {
    public static final String ALLOCATION_KEY_NAME = "allocation";
    public static final short CONNECT_PROTOCOL_V3 = 3;
    public static final short CONNECT_PROTOCOL_V4 = 4;

    /**
     * Connect Protocol Header V3:
     * <pre>
     *   Version            => Int16
     * </pre>
     */
    private static final Struct CONNECT_PROTOCOL_HEADER_V3 = new Struct(CONNECT_PROTOCOL_HEADER_SCHEMA)
            .set(VERSION_KEY_NAME, CONNECT_PROTOCOL_V3);

    /**
     * Connect Protocol Header V4:
     * <pre>
     *   Version            => Int16
     * </pre>
     * The V4 protocol is schematically identical to V3, but is used to signify that internal request
     * verification and distribution of session keys is enabled (for more information, see KIP-507:
     * https://cwiki.apache.org/confluence/display/KAFKA/KIP-507%3A+Securing+Internal+Connect+REST+Endpoints)
     */
    private static final Struct CONNECT_PROTOCOL_HEADER_V4 = new Struct(CONNECT_PROTOCOL_HEADER_SCHEMA)
            .set(VERSION_KEY_NAME, CONNECT_PROTOCOL_V4);

    /**
     * Config State V3:
     * <pre>
     *   Url                => [String]
     *   ConfigOffset       => Int64
     * </pre>
     */
    public static final Schema CONFIG_STATE_V3 = CONFIG_STATE_V0;

    /**
     * Allocation V3
     * <pre>
     *   Current Assignment => [Byte]
     * </pre>
     */
    public static final Schema ALLOCATION_V3 = ALLOCATION_V1;

    /**
     *
     * Connector Assignment V3:
     * <pre>
     *   Connector          => [String]
     *   Tasks              => [Int32]
     * </pre>
     *
     * <p>Assignments for each worker are a set of connectors and tasks. These are categorized by
     * connector ID. A sentinel task ID (CONNECTOR_TASK) is used to indicate the connector itself
     * (i.e. that the assignment includes responsibility for running the Connector instance in
     * addition to any tasks it generates).</p>
     */
    public static final Schema CONNECTOR_ASSIGNMENT_V3 = CONNECTOR_ASSIGNMENT_V0;

    /**
     * Raw (non versioned) assignment V3:
     * <pre>
     *   Error              => Int16
     *   Leader             => [String]
     *   LeaderUrl          => [String]
     *   ConfigOffset       => Int64
     *   Assignment         => [Connector Assignment]
     *   Revoked            => [Connector Assignment]
     *   ScheduledDelay     => Int32
     * </pre>
     */
    public static final Schema ASSIGNMENT_V3 = ASSIGNMENT_V1;

    /**
     * The fields are serialized in sequence as follows:
     * Subscription V3:
     * <pre>
     *   Version            => Int16
     *   Url                => [String]
     *   ConfigOffset       => Int64
     *   Current Assignment => [Byte]
     * </pre>
     */
    public static ByteBuffer serializeMetadata(ExtendedWorkerState workerState, boolean sessioned) {
        Struct configState = new Struct(CONFIG_STATE_V3)
                .set(URL_KEY_NAME, workerState.url())
                .set(CONFIG_OFFSET_KEY_NAME, workerState.offset());
        // Not a big issue if we embed the protocol version with the assignment in the metadata
        Struct allocation = new Struct(ALLOCATION_V3)
                .set(ALLOCATION_KEY_NAME, serializeAssignment(workerState.assignment()));
        Struct connectProtocolHeader = sessioned ? CONNECT_PROTOCOL_HEADER_V4 : CONNECT_PROTOCOL_HEADER_V3;
        ByteBuffer buffer = ByteBuffer.allocate(connectProtocolHeader.sizeOf()
                + CONFIG_STATE_V3.sizeOf(configState)
                + ALLOCATION_V3.sizeOf(allocation));
        connectProtocolHeader.writeTo(buffer);
        CONFIG_STATE_V3.write(buffer, configState);
        ALLOCATION_V3.write(buffer, allocation);
        buffer.flip();
        return buffer;
    }

    /**
     * Returns the collection of Connect protocols that are supported by this version along
     * with their serialized metadata. The protocols are ordered by preference.
     *
     * @param workerState the current state of the worker metadata
     * @param sessioned whether the {@link ConnectProtocolCompatibility#SESSIONED} protocol should
     *                  be included in the collection of supported protocols
     * @return the collection of Connect protocol metadata
     */
    public static JoinGroupRequestProtocolCollection metadataRequest(ExtendedWorkerState workerState, boolean sessioned) {
        // Order matters in terms of protocol preference
        List<JoinGroupRequestProtocol> joinGroupRequestProtocols = new ArrayList<>();
        if (sessioned) {
            joinGroupRequestProtocols.add(new JoinGroupRequestProtocol()
                    .setName(APM_SESSIONED.protocol())
                    .setMetadata(IncrementalCooperativeAPMConnectProtocol.serializeMetadata(workerState, true).array())
            );
            joinGroupRequestProtocols.add(new JoinGroupRequestProtocol()
                    .setName(SESSIONED.protocol())
                    .setMetadata(IncrementalCooperativeConnectProtocol.serializeMetadata(workerState, true).array())
            );
        }
        joinGroupRequestProtocols.add(new JoinGroupRequestProtocol()
                .setName(APM_COMPATIBLE.protocol())
                .setMetadata(IncrementalCooperativeAPMConnectProtocol.serializeMetadata(workerState, false).array())
        );
        joinGroupRequestProtocols.add(new JoinGroupRequestProtocol()
                .setName(COMPATIBLE.protocol())
                .setMetadata(IncrementalCooperativeConnectProtocol.serializeMetadata(workerState, false).array())
        );
        joinGroupRequestProtocols.add(new JoinGroupRequestProtocol()
                .setName(EAGER.protocol())
                .setMetadata(ConnectProtocol.serializeMetadata(workerState).array())
        );
        return new JoinGroupRequestProtocolCollection(joinGroupRequestProtocols.iterator());
    }

    /**
     * Given a byte buffer that contains protocol metadata return the deserialized form of the
     * metadata.
     *
     * @param buffer A buffer containing the protocols metadata
     * @return the deserialized metadata
     * @throws SchemaException on incompatible Connect protocol version
     */
    public static ExtendedWorkerState deserializeMetadata(ByteBuffer buffer) {
        Struct header = CONNECT_PROTOCOL_HEADER_SCHEMA.read(buffer);
        Short version = header.getShort(VERSION_KEY_NAME);
        checkVersionCompatibility(version);
        Struct configState = CONFIG_STATE_V3.read(buffer);
        long configOffset = configState.getLong(CONFIG_OFFSET_KEY_NAME);
        String url = configState.getString(URL_KEY_NAME);
        Struct allocation = ALLOCATION_V3.read(buffer);
        // Protocol version is embedded with the assignment in the metadata
        ExtendedAssignment assignment = deserializeAssignment(allocation.getBytes(ALLOCATION_KEY_NAME));
        return new ExtendedWorkerState(url, configOffset, assignment);
    }

    /**
     * The fields are serialized in sequence as follows:
     * Complete Assignment V3:
     * <pre>
     *   Version            => Int16
     *   Error              => Int16
     *   Leader             => [String]
     *   LeaderUrl          => [String]
     *   ConfigOffset       => Int64
     *   Assignment         => [Connector Assignment]
     *   Revoked            => [Connector Assignment]
     *   ScheduledDelay     => Int32
     * </pre>
     */
    public static ByteBuffer serializeAssignment(ExtendedAssignment assignment) {
        // comparison depends on reference equality for now
        if (assignment == null || ExtendedAssignment.empty().equals(assignment)) {
            return null;
        }
        Struct struct = assignment.toStruct();
        ByteBuffer buffer = ByteBuffer.allocate(CONNECT_PROTOCOL_HEADER_V3.sizeOf()
                + ASSIGNMENT_V3.sizeOf(struct));
        CONNECT_PROTOCOL_HEADER_V3.writeTo(buffer);
        ASSIGNMENT_V3.write(buffer, struct);
        buffer.flip();
        return buffer;
    }

    /**
     * Given a byte buffer that contains an assignment as defined by this protocol, return the
     * deserialized form of the assignment.
     *
     * @param buffer the buffer containing a serialized assignment
     * @return the deserialized assignment
     * @throws SchemaException on incompatible Connect protocol version
     */
    public static ExtendedAssignment deserializeAssignment(ByteBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        Struct header = CONNECT_PROTOCOL_HEADER_SCHEMA.read(buffer);
        Short version = header.getShort(VERSION_KEY_NAME);
        checkVersionCompatibility(version);
        Struct struct = ASSIGNMENT_V3.read(buffer);
        return ExtendedAssignment.fromStruct(version, struct);
    }

    private static void checkVersionCompatibility(short version) {
        // check for invalid versions
        if (version < CONNECT_PROTOCOL_V0)
            throw new SchemaException("Unsupported subscription version: " + version);

        // otherwise, assume versions can be parsed
    }
}