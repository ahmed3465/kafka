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
package org.apache.kafka.raft;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.apache.kafka.common.errors.NotLeaderOrFollowerException;
import org.apache.kafka.common.message.BeginQuorumEpochRequestData;
import org.apache.kafka.common.message.BeginQuorumEpochResponseData;
import org.apache.kafka.common.message.DescribeQuorumRequestData;
import org.apache.kafka.common.message.DescribeQuorumResponseData;
import org.apache.kafka.common.message.DescribeQuorumResponseData.ReplicaState;
import org.apache.kafka.common.message.EndQuorumEpochRequestData;
import org.apache.kafka.common.message.EndQuorumEpochResponseData;
import org.apache.kafka.common.message.FetchQuorumRecordsRequestData;
import org.apache.kafka.common.message.FetchQuorumRecordsResponseData;
import org.apache.kafka.common.message.FindQuorumRequestData;
import org.apache.kafka.common.message.FindQuorumResponseData;
import org.apache.kafka.common.message.LeaderChangeMessage;
import org.apache.kafka.common.message.LeaderChangeMessage.Voter;
import org.apache.kafka.common.message.VoteRequestData;
import org.apache.kafka.common.message.VoteResponseData;
import org.apache.kafka.common.message.VoteResponseData.VotePartitionResponse;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.Records;
import org.apache.kafka.common.requests.DescribeQuorumRequest;
import org.apache.kafka.common.requests.DescribeQuorumResponse;
import org.apache.kafka.common.requests.VoteRequest;
import org.apache.kafka.common.requests.VoteResponse;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Timer;
import org.apache.kafka.raft.ConnectionCache.ConnectionState;
import org.apache.kafka.raft.ConnectionCache.HostInfo;
import org.apache.kafka.raft.internals.KafkaRaftMetrics;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.apache.kafka.raft.RaftUtil.hasValidTopicPartition;

/**
 * This class implements a Kafkaesque version of the Raft protocol. Leader election
 * is more or less pure Raft, but replication is driven by replica fetching and we use Kafka's
 * log reconciliation protocol to truncate the log to a common point following each leader
 * election.
 *
 * Like Zookeeper, this protocol distinguishes between voters and observers. Voters are
 * the only ones who are eligible to handle protocol requests and they are the only ones
 * who take part in elections. The protocol does not yet support dynamic quorum changes.
 *
 * These are the APIs in this protocol:
 *
 * 1) {@link VoteRequestData}: Sent by valid voters when their election timeout expires and they
 *    become a candidate. This request includes the last offset in the log which electors use
 *    to tell whether or not to grant the vote.
 *
 * 2) {@link BeginQuorumEpochRequestData}: Sent by the leader of an epoch only to valid voters to
 *    assert its leadership of the new epoch. This request will be retried indefinitely for
 *    each voter until it acknowledges the request or a new election occurs.
 *
 *    This is not needed in usual Raft because the leader can use an empty data push
 *    to achieve the same purpose. The Kafka Raft implementation, however, is driven by
 *    fetch requests from followers, so there must be a way to find the new leader after
 *    an election has completed.
 *
 *    We might consider replacing this API and let followers use FindLeader even if they
 *    are voters.
 *
 * 3) {@link EndQuorumEpochRequestData}: Sent by the leader of an epoch to valid voters in order to
 *    gracefully resign from the current epoch. This causes remaining voters to immediately
 *    begin a new election.
 *
 * 4) {@link FetchQuorumRecordsRequestData}: This is basically the same as the usual Fetch API in
 *    Kafka, however the protocol implements it as a separate request type because there
 *    is additional metadata which we need to piggyback on responses. Unlike partition replication,
 *    we also piggyback truncation detection on this API rather than through a separate state.
 *
 * 5) {@link FindQuorumRequestData}: Sent by observers in order to find the leader. The leader
 *    is responsible for pushing BeginQuorumEpoch requests to other votes, but it is the responsibility
 *    of observers to find the current leader themselves. We could probably use one of the Fetch
 *    APIs for the same purpose, but we separated it initially for clarity.
 *
 */
public class KafkaRaftClient implements RaftClient {
    private final static int RETRY_BACKOFF_BASE_MS = 100;

    private final AtomicReference<GracefulShutdown> shutdown = new AtomicReference<>();
    private final Logger logger;

    /**
     * This timer is used to encapsulate several timeout mechanism of the client. It should be continuously updated with
     * new deadline time:
     *
     * 1. If the client is in leader state, the timer is for the fetch timeout from the majority of quorum;
     *    when it expires, the leader should try to use find-quorum to discover if there's a new leader.
     *
     * 2. If the client is in candidate state requesting votes from others, the timer is for the election timeout;
     *    when it expires, the candidate should treat the current election as already failed.
     *
     * 3. If the client is in candidate state completing a failed election, the timer is for the exponential election backoff
     *    based on the number of retries; when it expired, the candidate can bump the epoch and start the next election.
     *
     * 4. If the client is in the non-leader voter state, the timer is for the fetch timeout;
     *    when it expires, the voter should transit to candidate with bumped epoch and start the election.
     *
     * 5. If a non-leader voter received an end-epoch request from the old leader, it's timer would be set as the exponential
     *    election backoff based on the old leader's successor preference; when it expired, the voter would bump the epoch
     *    and start the election as a candidate.
     */
    private final Time time;
    private final Timer timer;

    private final int electionTimeoutMs;
    private final int electionBackoffMaxMs;
    private final int fetchTimeoutMs;
    private final int requestTimeoutMs;
    private final int fetchMaxWaitMs;
    private final long bootTimestamp;
    private final KafkaRaftMetrics kafkaRaftMetrics;
    private final InetSocketAddress advertisedListener;
    private final NetworkChannel channel;
    private final ReplicatedLog log;
    private final QuorumState quorum;
    private final Random random;
    private final ConnectionCache connections;
    private final FuturePurgatory<Void> purgatory;

    // unwritten appends are those which have not been applied to the leader's local log;
    // uncommitted appends are those which have applied to the leader's local log but have not committed among the quorum.
    private final BlockingQueue<UnwrittenAppend> unwrittenAppends;
    private final SortedMap<OffsetAndEpoch, AppendBatchAndTime> uncommittedAppends;

    private ReplicatedStateMachine stateMachine;

    public KafkaRaftClient(RaftConfig raftConfig,
                           NetworkChannel channel,
                           ReplicatedLog log,
                           QuorumState quorum,
                           Time time,
                           FuturePurgatory<Void> purgatory,
                           InetSocketAddress advertisedListener,
                           LogContext logContext) {
        this(channel,
            log,
            quorum,
            time,
            new Metrics(time),
            purgatory,
            advertisedListener,
            raftConfig.bootstrapServers(),
            raftConfig.electionTimeoutMs(),
            raftConfig.electionBackoffMaxMs(),
            raftConfig.fetchTimeoutMs(),
            raftConfig.retryBackoffMs(),
            raftConfig.requestTimeoutMs(),
            500,
            logContext,
            new Random());
    }

    public KafkaRaftClient(NetworkChannel channel,
                           ReplicatedLog log,
                           QuorumState quorum,
                           Time time,
                           Metrics metrics,
                           FuturePurgatory<Void> purgatory,
                           InetSocketAddress advertisedListener,
                           List<InetSocketAddress> bootstrapServers,
                           int electionTimeoutMs,
                           int electionBackoffMaxMs,
                           int fetchTimeoutMs,
                           int retryBackoffMs,
                           int requestTimeoutMs,
                           int fetchMaxWaitMs,
                           LogContext logContext,
                           Random random) {
        this.channel = channel;
        Objects.requireNonNull(log, "Log instance cannot be null");
        this.log = log;
        this.quorum = quorum;
        this.purgatory = purgatory;
        this.time = time;
        this.timer = time.timer(fetchTimeoutMs);    // initialize assuming it is an observer
        this.electionTimeoutMs = electionTimeoutMs;
        this.electionBackoffMaxMs = electionBackoffMaxMs;
        this.fetchTimeoutMs = fetchTimeoutMs;
        this.requestTimeoutMs = requestTimeoutMs;
        this.fetchMaxWaitMs = fetchMaxWaitMs;
        this.bootTimestamp = time.milliseconds();
        this.advertisedListener = advertisedListener;
        this.logger = logContext.logger(KafkaRaftClient.class);
        this.random = random;
        this.connections = new ConnectionCache(channel, bootstrapServers,
            retryBackoffMs, requestTimeoutMs, logContext);
        this.unwrittenAppends = new ArrayBlockingQueue<>(10);
        this.uncommittedAppends = new TreeMap<>();
        this.connections.maybeUpdate(quorum.localId, new HostInfo(advertisedListener, bootTimestamp));
        this.kafkaRaftMetrics = new KafkaRaftMetrics(metrics, "raft", quorum, bootTimestamp);
        kafkaRaftMetrics.updateNumUnknownVoterConnections(quorum.remoteVoters().size());
    }

    private void applyCommittedRecordsToStateMachine() {
        quorum.highWatermark().ifPresent(highWatermark -> {
            log.updateHighWatermark(highWatermark);
            maybeCommitPendingAppends(highWatermark, time.milliseconds());

            logger.debug("Applying committed entries up to high watermark {}. " +
                "Current position is {}", highWatermark, stateMachine.position());

            while (stateMachine.position().offset < highWatermark.offset && shutdown.get() == null) {
                OffsetAndEpoch position = stateMachine.position();
                Records records = readCommitted(position, highWatermark.offset);
                if (records.sizeInBytes() == 0)
                    break;

                stateMachine.apply(records);
                logger.trace("Applied committed records at {} to the state machine; position " +
                    "updated to {}", position, stateMachine.position());
            }
        });
    }

    private void maybeCommitPendingAppends(LogOffsetMetadata highWatermark, long currentTimeMs) {
        Iterator<Map.Entry<OffsetAndEpoch, AppendBatchAndTime>> iter = uncommittedAppends.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<OffsetAndEpoch, AppendBatchAndTime> entry = iter.next();
            OffsetAndEpoch offsetAndEpoch = entry.getKey();
            if (offsetAndEpoch.offset < highWatermark.offset) {
                AppendBatchAndTime appendBatchAndTime = entry.getValue();
                long elapsedTime = Math.max(0, currentTimeMs - appendBatchAndTime.appendTimeMs);
                double elapsedTimePerRecord = elapsedTime / (double) appendBatchAndTime.numRecords;
                kafkaRaftMetrics.updateCommitLatency(elapsedTimePerRecord, currentTimeMs);
                iter.remove();
            } else {
                break;
            }
        }
    }

    private void updateFollowerHighWatermark(FollowerState state, OptionalLong highWatermarkOpt) {
        highWatermarkOpt.ifPresent(highWatermark -> {
            long newHighWatermark = Math.min(endOffset().offset, highWatermark);
            state.updateHighWatermark(OptionalLong.of(newHighWatermark));
            logger.debug("Follower high watermark updated to {}", newHighWatermark);
            applyCommittedRecordsToStateMachine();
        });
    }

    private void updateLeaderEndOffsetAndTimestamp(LeaderState state) {
        if (state.updateLocalState(time.milliseconds(),
            this::resetFetchTimeout,
            log.endOffset())) {
            logger.debug("Leader high watermark updated to {} after end offset updated to {}",
                    state.highWatermark(), log.endOffset());
            applyCommittedRecordsToStateMachine();
        }

        // We may have pending fetches that can be completed by the advancement
        // of the log end offset
        purgatory.completeAll(null);
    }

    private void updateReplicaEndOffset(LeaderState state, int replicaId, LogOffsetMetadata endOffsetMetadata) {
        if (state.updateReplicaState(replicaId,
            time.milliseconds(),
            this::resetFetchTimeout,
            endOffsetMetadata)) {
            logger.debug("Leader high watermark updated to {} after replica {} end offset updated to {}",
                    state.highWatermark(), replicaId, endOffsetMetadata);
            applyCommittedRecordsToStateMachine();
        }
    }

    private void resetFetchTimeout(long updatedFetchTimestamp) {
        timer.resetDeadline(updatedFetchTimestamp + fetchTimeoutMs);
    }

    @Override
    public void initialize(ReplicatedStateMachine stateMachine) throws IOException {
        this.stateMachine = stateMachine;
        quorum.initialize(new OffsetAndEpoch(log.endOffset().offset, log.lastFetchedEpoch()));
        stateMachine.initialize(this::append);

        if (quorum.isLeader()) {
            onBecomeLeader(quorum.leaderStateOrThrow());
        } else if (quorum.isCandidate()) {
            // If the quorum consists of a single node, we can become leader immediately
            onBecomeCandidate(quorum.candidateStateOrThrow());
        } else if (quorum.isFollower()) {
            FollowerState state = quorum.followerStateOrThrow();
            if (quorum.isVoter() && quorum.epoch() == 0) {
                // If we're initializing for the first time, become a candidate immediately
                becomeCandidate();
            } else if (state.hasLeader()) {
                onBecomeFollowerOfElectedLeader(quorum.followerStateOrThrow(), state.leaderId());
            }
        }
    }

    private OffsetAndEpoch endOffset() {
        return new OffsetAndEpoch(log.endOffset().offset, log.lastFetchedEpoch());
    }

    private void resetConnections() {
        connections.resetAll();
    }

    private void onBecomeLeader(LeaderState state) {
        stateMachine.becomeLeader(quorum.epoch());
        updateLeaderEndOffsetAndTimestamp(state);

        // Add a control message for faster high watermark advance.
        final long currentTimeMs = time.milliseconds();
        appendControlRecord(MemoryRecords.withLeaderChangeMessage(
            currentTimeMs,
            quorum.epoch(),
            new LeaderChangeMessage()
                .setLeaderId(state.election().leaderId())
                .setVoters(
                    state.followers().stream().map(
                        follower -> new Voter().setVoterId(follower)).collect(Collectors.toList())))
        );

        log.assignEpochStartOffset(quorum.epoch(), log.endOffset().offset);
        resetConnections();

        timer.reset(fetchTimeoutMs);
        kafkaRaftMetrics.maybeUpdateElectionLatency(currentTimeMs);

        logger.info("{} become the leader for epoch {}", state.localId(), state.epoch());
    }

    private void appendControlRecord(Records controlRecord) {
        if (shutdown.get() != null)
            throw new IllegalStateException("Cannot append records while we are shutting down");
        log.appendAsLeader(controlRecord, quorum.epoch());
        kafkaRaftMetrics.updateAppendRecords(1);
        kafkaRaftMetrics.updateLogEnd(endOffset());
    }

    private boolean maybeBecomeLeader(CandidateState state) throws IOException {
        if (state.isVoteGranted()) {
            long endOffset = log.endOffset().offset;
            LeaderState leaderState = quorum.becomeLeader(endOffset);
            onBecomeLeader(leaderState);

            return true;
        } else {
            return false;
        }
    }

    private void onBecomeCandidate(CandidateState state) throws IOException {
        if (!maybeBecomeLeader(state)) {
            resetConnections();

            kafkaRaftMetrics.updateElectionStartMs(time.milliseconds());
            timer.reset(randomElectionTimeoutMs());

            logger.info("{} become the candidate for epoch {}", state.localId(), state.epoch());
        }
    }

    private void becomeCandidate() throws IOException {
        // after we become candidate, we may not fetch from leader any more
        CandidateState state = quorum.becomeCandidate();
        onBecomeCandidate(state);
    }

    private void becomeUnattachedFollower(int epoch) throws IOException {
        if (quorum.becomeUnattachedFollower(epoch)) {
            // if we are voter, becoming unattached also means that we would no longer fetch
            // from whoever the old leader already, while there's no new leader learned yet.
            // So this member should qualify to elect as leader as well, and hence we shorten
            // the timer to election timeout;
            //
            // if we are observer, nothing needed to be done as we would still try to find-quorum eventually
            if (quorum.isVoter()) {
                timer.reset(Math.min(timer.remainingMs(), randomElectionTimeoutMs()));
            }

            onBecomeFollower();
        }
    }

    private void becomeVotedFollower(int candidateId, int epoch) throws IOException {
        if (quorum.becomeVotedFollower(epoch, candidateId)) {
            // even though we've voted for someone, it still means that the previous leader
            // would be replaced by a new one and there's no clear winner yet, so
            // setting the timer to election timeout would allow this member
            // to participate in the election as well
            timer.reset(randomElectionTimeoutMs());
            onBecomeFollower();
        }
    }

    private void onBecomeFollower() {
        resetConnections();

        // After becoming a follower, we need to complete pending fetches so that
        // they can be resent to the leader without waiting for their expiration
        purgatory.completeAll(null);

        // TODO: we may only return the future when the append is committed not appended locally,
        //       in which case we would change this logic
        uncommittedAppends.clear();

        for (UnwrittenAppend unwrittenAppend: unwrittenAppends) {
            if (!unwrittenAppend.isCancelled()) {
                unwrittenAppend.fail(new NotLeaderOrFollowerException(
                    "Append refused since this node is no longer the leader"));
            }
        }
        unwrittenAppends.clear();

        logger.info("{} become a {} for epoch {}", quorum.localId, quorum.isVoter() ? "follower" : "observer", quorum.epoch());
    }

    private void onBecomeFollowerOfElectedLeader(FollowerState state, int leaderId) {
        stateMachine.becomeFollower(state.epoch(), leaderId);

        timer.reset(fetchTimeoutMs);

        kafkaRaftMetrics.maybeUpdateElectionLatency(time.milliseconds());

        onBecomeFollower();
    }

    private void becomeFetchingFollower(int leaderId, int epoch) throws IOException {
        if (quorum.becomeFetchingFollower(epoch, leaderId)) {
            onBecomeFollowerOfElectedLeader(quorum.followerStateOrThrow(), leaderId);
        }
    }

    private void becomeFollower(OptionalInt leaderId, int epoch) throws IOException {
        if (leaderId.isPresent()) {
            becomeFetchingFollower(leaderId.getAsInt(), epoch);
        } else {
            becomeUnattachedFollower(epoch);
        }
    }

    private VoteResponseData buildVoteResponse(Errors partitionLevelError, boolean voteGranted) {
        return VoteResponse.singletonResponse(
            Errors.NONE,
            log.topicPartition(),
            partitionLevelError,
            quorum.epoch(),
            quorum.leaderIdOrNil(),
            voteGranted);
    }

    /**
     * Handle a Vote request. This API may return the following errors:
     *
     * - {@link Errors#BROKER_NOT_AVAILABLE} if this node is currently shutting down
     * - {@link Errors#FENCED_LEADER_EPOCH} if the epoch is smaller than this node's epoch
     * - {@link Errors#INCONSISTENT_VOTER_SET} if the request suggests inconsistent voter membership (e.g.
     *      if this node or the sender is not one of the current known voters)
     * - {@link Errors#INVALID_REQUEST} if the last epoch or offset are invalid
     */
    private VoteResponseData handleVoteRequest(
        RaftRequest.Inbound requestMetadata
    ) throws IOException {
        VoteRequestData request = (VoteRequestData) requestMetadata.data;

        if (!hasValidTopicPartition(request, log.topicPartition())) {
            return VoteRequest.getPartitionLevelErrorResponse(request, Errors.UNKNOWN_TOPIC_OR_PARTITION);
        }

        VoteRequestData.VotePartitionRequest partitionRequest =
            request.topics().get(0).partitions().get(0);

        int candidateId = partitionRequest.candidateId();
        int candidateEpoch = partitionRequest.candidateEpoch();

        int lastEpoch = partitionRequest.lastOffsetEpoch();
        long lastEpochEndOffset = partitionRequest.lastOffset();
        if (lastEpochEndOffset < 0 || lastEpoch < 0 || lastEpoch >= candidateEpoch) {
            return buildVoteResponse(Errors.INVALID_REQUEST, false);
        }

        Optional<Errors> errorOpt = validateVoterOnlyRequest(candidateId, candidateEpoch);
        if (errorOpt.isPresent()) {
            return buildVoteResponse(errorOpt.get(), false);
        }

        OffsetAndEpoch lastEpochEndOffsetAndEpoch = new OffsetAndEpoch(lastEpochEndOffset, lastEpoch);
        boolean voteGranted = lastEpochEndOffsetAndEpoch.compareTo(endOffset()) >= 0;

        // we already confirmed that candidateEpoch >= quorum.epoch()
        if (candidateEpoch > quorum.epoch()) {
            if (!voteGranted) {
                // even if we rejected the vote, we'd still need to bump our epoch
                // by transiting to unattached follower and resetting timer
                becomeUnattachedFollower(candidateEpoch);
            }
        } else if (quorum.isLeader()) {
            logger.debug("Ignoring vote request {} with epoch {} since we are already leader on that epoch",
                    request, candidateEpoch);
            voteGranted = false;
        } else if (quorum.isCandidate()) {
            logger.debug("Ignoring vote request {} with epoch {} since we are already candidate on that epoch",
                    request, candidateEpoch);
            voteGranted = false;
        } else {
            FollowerState state = quorum.followerStateOrThrow();

            if (state.hasLeader()) {
                logger.debug("Rejecting vote request {} with epoch {} since we already have a leader {} on that epoch",
                    request, candidateEpoch, state.leaderId());
                voteGranted = false;
            } else if (state.hasVoted()) {
                voteGranted = state.hasVotedFor(candidateId);
                if (!voteGranted) {
                    logger.debug("Rejecting vote request {} with epoch {} since we already have voted for " +
                        "another candidate {} on that epoch", request, candidateEpoch, state.votedId());
                }
            }
        }

        if (voteGranted) {
            becomeVotedFollower(candidateId, candidateEpoch);
        }

        logger.info("Vote request {} is {}", request, voteGranted ? "granted" : "rejected");
        return buildVoteResponse(Errors.NONE, voteGranted);
    }

    private boolean handleVoteResponse(
        RaftResponse.Inbound responseMetadata
    ) throws IOException {
        int remoteNodeId = responseMetadata.sourceId();
        VoteResponseData response = (VoteResponseData) responseMetadata.data;

        if (!hasValidTopicPartition(response, log.topicPartition())) {
            return false;
        }

        if (Errors.forCode(response.errorCode()) != Errors.NONE) {
            return false;
        }

        VotePartitionResponse partitionResponse = response.topics().get(0).partitions().get(0);

        Errors error = Errors.forCode(partitionResponse.errorCode());
        OptionalInt responseLeaderId = optionalLeaderId(partitionResponse.leaderId());
        int responseEpoch = partitionResponse.leaderEpoch();

        Optional<Boolean> handled = maybeHandleCommonResponse(error, responseLeaderId, responseEpoch);
        if (handled.isPresent()) {
            return handled.get();
        } else if (error == Errors.NONE) {
            if (quorum.isLeader()) {
                logger.debug("Ignoring vote response {} since we already became leader for epoch {}",
                    partitionResponse, quorum.epoch());
            } else if (quorum.isFollower()) {
                logger.debug("Ignoring vote response {} since we are now a follower for epoch {}",
                    partitionResponse, quorum.epoch());
            } else {
                CandidateState state = quorum.candidateStateOrThrow();
                if (partitionResponse.voteGranted()) {
                    state.recordGrantedVote(remoteNodeId);
                    maybeBecomeLeader(state);
                } else {
                    state.recordRejectedVote(remoteNodeId);
                    if (state.isVoteRejected() && !state.isBackingOff()) {
                        logger.info("Insufficient remaining votes to become leader (rejected by {}). " +
                            "We will backoff before retrying election again", state.rejectingVoters());

                        state.startBackingOff();
                        timer.reset(binaryExponentialElectionBackoffMs(state.retries()));
                    }
                }
            }
            return true;
        } else {
            return handleUnexpectedError(error, responseMetadata);
        }
    }

    private int randomElectionTimeoutMs() {
        if (electionTimeoutMs == 0)
            return 0;
        return electionTimeoutMs + random.nextInt(electionTimeoutMs);
    }

    private int binaryExponentialElectionBackoffMs(int retries) {
        if (retries <= 0) {
            throw new IllegalArgumentException("Retries " + retries + " should be larger than zero");
        }

        return Math.min(RETRY_BACKOFF_BASE_MS * random.nextInt(2 << (retries - 1)), electionBackoffMaxMs);
    }

    private int strictExponentialElectionBackoffMs(int positionInSuccessors, int totalNumSuccessors) {
        if (positionInSuccessors <= 0 || positionInSuccessors >= totalNumSuccessors) {
            throw new IllegalArgumentException("Position " + positionInSuccessors + " should be larger than zero" +
                    " and smaller than total number of successors " + totalNumSuccessors);
        }

        int retryBackOffBaseMs = electionBackoffMaxMs >> (totalNumSuccessors - 1);
        return Math.min(electionBackoffMaxMs, retryBackOffBaseMs << (positionInSuccessors - 1));
    }


    private BeginQuorumEpochResponseData buildBeginQuorumEpochResponse(Errors error) {
        return new BeginQuorumEpochResponseData()
                .setErrorCode(error.code())
                .setLeaderEpoch(quorum.epoch())
                .setLeaderId(quorum.leaderIdOrNil());
    }

    /**
     * Handle a BeginEpoch request. This API may return the following errors:
     *
     * - {@link Errors#BROKER_NOT_AVAILABLE} if this node is currently shutting down
     * - {@link Errors#INCONSISTENT_VOTER_SET} if the request suggests inconsistent voter membership (e.g.
     *      if this node or the sender is not one of the current known voters)
     * - {@link Errors#FENCED_LEADER_EPOCH} if the epoch is smaller than this node's epoch
     */
    private BeginQuorumEpochResponseData handleBeginQuorumEpochRequest(
        RaftRequest.Inbound requestMetadata
    ) throws IOException {
        BeginQuorumEpochRequestData request = (BeginQuorumEpochRequestData) requestMetadata.data;
        Optional<Errors> errorOpt = validateVoterOnlyRequest(request.leaderId(), request.leaderEpoch());
        if (errorOpt.isPresent()) {
            return buildBeginQuorumEpochResponse(errorOpt.get());
        } else {
            int requestLeaderId = request.leaderId();
            int requestEpoch = request.leaderEpoch();
            becomeFetchingFollower(requestLeaderId, requestEpoch);
            return buildBeginQuorumEpochResponse(Errors.NONE);
        }
    }

    private boolean handleBeginQuorumEpochResponse(
        RaftResponse.Inbound responseMetadata
    ) throws IOException {
        int remoteNodeId = responseMetadata.sourceId();
        BeginQuorumEpochResponseData response = (BeginQuorumEpochResponseData) responseMetadata.data;
        Errors error = Errors.forCode(response.errorCode());
        OptionalInt responseLeaderId = optionalLeaderId(response.leaderId());
        int responseEpoch = response.leaderEpoch();

        Optional<Boolean> handled = maybeHandleCommonResponse(error, responseLeaderId, responseEpoch);
        if (handled.isPresent()) {
            return handled.get();
        } else if (error == Errors.NONE) {
            LeaderState state = quorum.leaderStateOrThrow();
            state.addEndorsementFrom(remoteNodeId);
            return true;
        } else {
            return handleUnexpectedError(error, responseMetadata);
        }
    }

    private EndQuorumEpochResponseData buildEndQuorumEpochResponse(Errors error) {
        return new EndQuorumEpochResponseData()
                .setErrorCode(error.code())
                .setLeaderEpoch(quorum.epoch())
                .setLeaderId(quorum.leaderIdOrNil());
    }

    /**
     * Handle an EndEpoch request. This API may return the following errors:
     *
     * - {@link Errors#BROKER_NOT_AVAILABLE} if this node is currently shutting down
     * - {@link Errors#INCONSISTENT_VOTER_SET} if the request suggests inconsistent voter membership (e.g.
     *      if this node or the sender is not one of the current known voters)
     * - {@link Errors#FENCED_LEADER_EPOCH} if the epoch is smaller than this node's epoch
     */
    private EndQuorumEpochResponseData handleEndQuorumEpochRequest(
        RaftRequest.Inbound requestMetadata
    ) throws IOException {
        EndQuorumEpochRequestData request = (EndQuorumEpochRequestData) requestMetadata.data;
        int requestEpoch = request.leaderEpoch();
        int requestReplicaId = request.replicaId();

        Optional<Errors> errorOpt = validateVoterOnlyRequest(requestReplicaId, requestEpoch);
        if (errorOpt.isPresent()) {
            return buildEndQuorumEpochResponse(errorOpt.get());
        }

        // We still update our state if the request indicates a larger epoch
        OptionalInt requestLeaderId = optionalLeaderId(request.leaderId());
        maybeBecomeFollower(requestLeaderId, requestEpoch);

        if (quorum.isFollower()) {
            FollowerState state = quorum.followerStateOrThrow();
            if (state.isUnattached()
                || state.hasLeader(requestReplicaId)
                || state.hasVotedFor(requestReplicaId)) {
                List<Integer> preferredSuccessors = request.preferredSuccessors();
                // We didn't find the corresponding voters inside the request.
                if (!preferredSuccessors.contains(quorum.localId)) {
                    return buildEndQuorumEpochResponse(Errors.INCONSISTENT_VOTER_SET);
                }
                final int position = preferredSuccessors.indexOf(quorum.localId);
                // Based on the priority inside the preferred successors, choose the corresponding delayed
                // election backoff time based on strict exponential mechanism so that the most up-to-date voter
                // has a higher chance to be elected. If the node's priority is highest, become candidate immediately
                // instead of waiting for next poll.
                if (position == 0) {
                    becomeCandidate();
                } else {
                    timer.reset(strictExponentialElectionBackoffMs(position, preferredSuccessors.size()));
                }

            }
        } // else if we are already leader or a candidate, then we take no action

        return buildEndQuorumEpochResponse(Errors.NONE);
    }

    private boolean handleEndQuorumEpochResponse(
        RaftResponse.Inbound responseMetadata
    ) throws IOException {
        EndQuorumEpochResponseData response = (EndQuorumEpochResponseData) responseMetadata.data;
        Errors error = Errors.forCode(response.errorCode());
        OptionalInt responseLeaderId = optionalLeaderId(response.leaderId());
        int responseEpoch = response.leaderEpoch();

        Optional<Boolean> handled = maybeHandleCommonResponse(error, responseLeaderId, responseEpoch);
        if (handled.isPresent()) {
            return handled.get();
        } else if (error == Errors.NONE) {
            return true;
        } else {
            return handleUnexpectedError(error, responseMetadata);
        }
    }

    private FetchQuorumRecordsResponseData buildFetchQuorumRecordsResponse(
        Errors error,
        Records records,
        Optional<LogOffsetMetadata> highWatermark
    ) throws IOException {
        return buildEmptyFetchQuorumRecordsResponse(error, highWatermark)
            .setRecords(RaftUtil.serializeRecords(records));
    }

    private FetchQuorumRecordsResponseData buildEmptyFetchQuorumRecordsResponse(
        Errors error,
        Optional<LogOffsetMetadata> highWatermark
    ) {
        return new FetchQuorumRecordsResponseData()
            .setErrorCode(error.code())
            .setLeaderEpoch(quorum.epoch())
            .setLeaderId(quorum.leaderIdOrNil())
            .setRecords(ByteBuffer.wrap(new byte[0]))
            .setHighWatermark(highWatermark.map(logOffsetMetadata -> logOffsetMetadata.offset).orElse(-1L));
    }

    /**
     * Handle a FetchQuorumRecords request. The fetch offset and last fetched epoch are always
     * validated against the current log. In the case that they do not match, the response will
     * indicate the diverging offset/epoch. A follower is expected to truncate its log in this
     * case and resend the fetch.
     *
     * This API may return the following errors:
     *
     * - {@link Errors#BROKER_NOT_AVAILABLE} if this node is currently shutting down
     * - {@link Errors#FENCED_LEADER_EPOCH} if the epoch is smaller than this node's epoch
     * - {@link Errors#INVALID_REQUEST} if the request epoch is larger than the leader's current epoch
     *     or if either the fetch offset or the last fetched epoch is invalid
     */
    private CompletableFuture<FetchQuorumRecordsResponseData> handleFetchQuorumRecordsRequest(
        RaftRequest.Inbound requestMetadata
    ) throws IOException {
        FetchQuorumRecordsRequestData request = (FetchQuorumRecordsRequestData) requestMetadata.data;
        if (request.maxWaitTimeMs() < 0
            || request.fetchOffset() < 0
            || request.lastFetchedEpoch() < 0
            || request.lastFetchedEpoch() > request.leaderEpoch()) {
            return completedFuture(buildEmptyFetchQuorumRecordsResponse(
                Errors.INVALID_REQUEST, Optional.empty()));
        }

        FetchQuorumRecordsResponseData response = tryCompleteFetchQuorumRecordsRequest(request);
        if (response.errorCode() != Errors.NONE.code()
            || response.records().hasRemaining()
            || request.maxWaitTimeMs() == 0) {
            return completedFuture(response);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        purgatory.await(future, request.maxWaitTimeMs());

        return future.handle((nil, exception) -> {
            if (exception != null) {
                Throwable cause = exception instanceof ExecutionException ?
                    exception.getCause() : exception;

                // If the fetch timed out in purgatory, it means no new data is available,
                // and we will complete the fetch successfully. Otherwise, if there was
                // any other error, we need to return it.
                Errors error = Errors.forException(cause);
                if (error != Errors.REQUEST_TIMED_OUT) {
                    return buildEmptyFetchQuorumRecordsResponse(error, Optional.empty());
                }
            }

            try {
                return tryCompleteFetchQuorumRecordsRequest(request);
            } catch (Exception e) {
                logger.error("Caught unexpected error in fetch completion of request {}", request, e);
                return buildEmptyFetchQuorumRecordsResponse(Errors.UNKNOWN_SERVER_ERROR, Optional.empty());
            }
        });
    }

    private FetchQuorumRecordsResponseData tryCompleteFetchQuorumRecordsRequest(
        FetchQuorumRecordsRequestData request
    ) throws IOException {
        Optional<Errors> errorOpt = validateLeaderOnlyRequest(request.leaderEpoch());
        if (errorOpt.isPresent()) {
            return buildFetchQuorumRecordsResponse(
                errorOpt.get(),
                MemoryRecords.EMPTY,
                Optional.empty());
        }

        int replicaId = request.replicaId();
        long fetchOffset = request.fetchOffset();
        int lastFetchedEpoch = request.lastFetchedEpoch();
        LeaderState state = quorum.leaderStateOrThrow();
        Optional<LogOffsetMetadata> highWatermark = state.highWatermark();
        Optional<OffsetAndEpoch> nextOffsetOpt = validateFetchOffsetAndEpoch(fetchOffset, lastFetchedEpoch);

        if (nextOffsetOpt.isPresent()) {
            OffsetAndEpoch nextOffsetAndEpoch = nextOffsetOpt.get();
            return buildEmptyFetchQuorumRecordsResponse(Errors.NONE, highWatermark)
                .setNextFetchOffset(nextOffsetAndEpoch.offset)
                .setNextFetchOffsetEpoch(nextOffsetAndEpoch.epoch);
        } else {
            LogFetchInfo info = log.read(fetchOffset, OptionalLong.empty());
            updateReplicaEndOffset(state, replicaId, info.startOffsetMetadata);

            return buildFetchQuorumRecordsResponse(
                    Errors.NONE,
                    info.records,
                    highWatermark);
        }
    }

    /**
     * Check whether a fetch offset and epoch is valid. Return the offset and epoch to truncate
     * to in case it is not.
     */
    private Optional<OffsetAndEpoch> validateFetchOffsetAndEpoch(long fetchOffset, int lastFetchedEpoch) {
        OffsetAndEpoch endOffsetAndEpoch = log.endOffsetForEpoch(lastFetchedEpoch)
            .orElse(new OffsetAndEpoch(-1L, -1));
        if (endOffsetAndEpoch.epoch != lastFetchedEpoch || endOffsetAndEpoch.offset < fetchOffset) {
            return Optional.of(endOffsetAndEpoch);
        } else {
            return Optional.empty();
        }
    }

    private OptionalInt optionalLeaderId(int leaderIdOrNil) {
        if (leaderIdOrNil < 0)
            return OptionalInt.empty();
        return OptionalInt.of(leaderIdOrNil);
    }

    private boolean handleFetchQuorumRecordsResponse(
        RaftResponse.Inbound responseMetadata
    ) throws IOException {
        FetchQuorumRecordsResponseData response = (FetchQuorumRecordsResponseData) responseMetadata.data;
        Errors error = Errors.forCode(response.errorCode());
        OptionalInt responseLeaderId = optionalLeaderId(response.leaderId());
        int responseEpoch = response.leaderEpoch();

        Optional<Boolean> handled = maybeHandleCommonResponse(error, responseLeaderId, responseEpoch);
        if (handled.isPresent()) {
            return handled.get();
        }

        FollowerState state = quorum.followerStateOrThrow();
        if (error == Errors.NONE) {
            if (response.nextFetchOffset() > 0) {
                // The leader is asking us to truncate before continuing
                OffsetAndEpoch nextFetchOffsetAndEpoch = new OffsetAndEpoch(
                    response.nextFetchOffset(), response.nextFetchOffsetEpoch());

                log.truncateToEndOffset(nextFetchOffsetAndEpoch).ifPresent(truncationOffset -> {
                    logger.info("Truncated to offset {} after out of range error from leader {}",
                        truncationOffset, quorum.leaderIdOrNil());
                });
            } else {
                ByteBuffer recordsBuffer = response.records();
                MemoryRecords records = MemoryRecords.readableRecords(recordsBuffer);
                if (records.sizeInBytes() > 0) {
                    LogAppendInfo info = log.appendAsFollower(records);
                    OffsetAndEpoch endOffset = endOffset();
                    kafkaRaftMetrics.updateFetchedRecords(info.lastOffset - info.firstOffset + 1);
                    kafkaRaftMetrics.updateLogEnd(endOffset);
                    logger.trace("Follower end offset updated to {} after append", endOffset);
                }
                OptionalLong highWatermark = response.highWatermark() < 0 ?
                    OptionalLong.empty() : OptionalLong.of(response.highWatermark());
                updateFollowerHighWatermark(state, highWatermark);
            }

            timer.reset(fetchTimeoutMs);
            return true;
        } else {
            return handleUnexpectedError(error, responseMetadata);
        }
    }

    private LogAppendInfo maybeAppendAsLeader(LeaderState state, Records records) {
        if (state.highWatermark().isPresent() && stateMachine.accept(records)) {
            LogAppendInfo info = log.appendAsLeader(records, quorum.epoch());
            OffsetAndEpoch endOffset = endOffset();
            updateLeaderEndOffsetAndTimestamp(state);
            kafkaRaftMetrics.updateAppendRecords(info.lastOffset - info.firstOffset + 1);
            kafkaRaftMetrics.updateLogEnd(endOffset);
            logger.trace("Leader appended records at base offset {}, new end offset is {}", info.firstOffset, endOffset);
            return info;
        }
        return null;
    }

    /**
     * Handle a FindQuorum request. Currently this API does not return any application errors.
     */
    private FindQuorumResponseData handleFindQuorumRequest(
        RaftRequest.Inbound requestMetadata
    ) {
        FindQuorumRequestData request = (FindQuorumRequestData) requestMetadata.data;
        HostInfo hostInfo = new HostInfo(
            new InetSocketAddress(request.host(), request.port()),
            request.bootTimestamp()
        );
        if (quorum.isVoter(request.replicaId())) {
            connections.maybeUpdate(request.replicaId(), hostInfo);
        }

        List<FindQuorumResponseData.Voter> knownVoterConnections = allVoterConnections();
        kafkaRaftMetrics.updateNumUnknownVoterConnections(quorum.allVoters().size() - knownVoterConnections.size());

        return new FindQuorumResponseData()
            .setErrorCode(Errors.NONE.code())
            .setLeaderEpoch(quorum.epoch())
            .setLeaderId(quorum.leaderIdOrNil())
            .setVoters(knownVoterConnections);
    }

    private List<FindQuorumResponseData.Voter> allVoterConnections() {
        List<FindQuorumResponseData.Voter> voters = new ArrayList<>();
        for (Map.Entry<Integer, Optional<HostInfo>> voterEntry : connections.allVoters().entrySet()) {
            FindQuorumResponseData.Voter voter = new FindQuorumResponseData.Voter();
            voter.setVoterId(voterEntry.getKey());
            voterEntry.getValue().ifPresent(voterHostInfo -> {
                voter.setHost(voterHostInfo.address.getHostString())
                    .setPort(voterHostInfo.address.getPort())
                    .setBootTimestamp(voterHostInfo.bootTimestamp);
            });
            voters.add(voter);
        }

        return voters;
    }

    private boolean handleFindQuorumResponse(
        RaftResponse.Inbound responseMetadata
    ) throws IOException {
        FindQuorumResponseData response = (FindQuorumResponseData) responseMetadata.data;
        Errors error = Errors.forCode(response.errorCode());
        OptionalInt responseLeaderId = optionalLeaderId(response.leaderId());
        int responseEpoch = response.leaderEpoch();

        // Always update voter connections if they are present, regardless of errors
        updateVoterConnections(response);

        Optional<Boolean> handled = maybeHandleCommonResponse(error, responseLeaderId, responseEpoch);
        if (handled.isPresent()) {
            return handled.get();
        } else if (error == Errors.NONE) {
            maybeBecomeFollower(responseLeaderId, responseEpoch);
            return true;
        } else {
            return handleUnexpectedError(error, responseMetadata);
        }
    }

    private DescribeQuorumResponseData handleDescribeQuorumRequest(
        RaftRequest.Inbound requestMetadata
    ) {
        DescribeQuorumRequestData describeQuorumRequestData = (DescribeQuorumRequestData) requestMetadata.data;
        if (!hasValidTopicPartition(describeQuorumRequestData, log.topicPartition())) {
            return DescribeQuorumRequest.getPartitionLevelErrorResponse(
                describeQuorumRequestData, Errors.UNKNOWN_TOPIC_OR_PARTITION);
        }

        if (!quorum.isLeader()) {
            return DescribeQuorumRequest.getTopLevelErrorResponse(Errors.INVALID_REQUEST);
        }

        LeaderState leaderState = quorum.leaderStateOrThrow();
        return DescribeQuorumResponse.singletonResponse(log.topicPartition(),
            leaderState.localId(),
            leaderState.epoch(),
            leaderState.highWatermark().isPresent() ? leaderState.highWatermark().get().offset : -1,
            convertToReplicaStates(leaderState.getVoterEndOffsets()),
            convertToReplicaStates(leaderState.getObserverStates(time.milliseconds()))
        );
    }

    List<ReplicaState> convertToReplicaStates(Map<Integer, Long> replicaEndOffsets) {
        return replicaEndOffsets.entrySet().stream()
                   .map(entry -> new ReplicaState()
                                     .setReplicaId(entry.getKey())
                                     .setLogEndOffset(entry.getValue()))
                   .collect(Collectors.toList());
    }

    private void updateVoterConnections(FindQuorumResponseData response) {
        for (FindQuorumResponseData.Voter voter : response.voters()) {
            if (voter.host() == null || voter.host().isEmpty())
                continue;
            InetSocketAddress voterAddress = new InetSocketAddress(voter.host(), voter.port());
            connections.maybeUpdate(voter.voterId(),
                new HostInfo(voterAddress, voter.bootTimestamp()));
        }

        kafkaRaftMetrics.updateNumUnknownVoterConnections(quorum.allVoters().size() - connections.allVoters().size());
    }

    private boolean hasConsistentLeader(int epoch, OptionalInt leaderId) {
        // Only elected leaders are sent in the request/response header, so if we have an elected
        // leaderId, it should be consistent with what is in the message.
        if (leaderId.isPresent() && leaderId.getAsInt() == quorum.localId) {
            // The response indicates that we should be the leader, so we verify that is the case
            return quorum.isLeader();
        } else {
            return epoch != quorum.epoch()
                || !leaderId.isPresent()
                || !quorum.leaderId().isPresent()
                || leaderId.equals(quorum.leaderId());
        }
    }

    /**
     * Handle response errors that are common across request types.
     *
     * @param error Error from the received response
     * @param leaderId Optional leaderId from the response
     * @param epoch Epoch received from the response
     * @return Optional value indicating whether the error was handled here and the outcome of
     *    that handling. Specifically:
     *
     *    - Optional.empty means that the response was not handled here and the custom
     *        API handler should be applied
     *    - Optional.of(true) indicates that the response was successfully handled here and
     *        the request does not need to be retried
     *    - Optional.of(false) indicates that the response was handled here, but that the request
     *        will need to be retried
     */
    private Optional<Boolean> maybeHandleCommonResponse(
        Errors error,
        OptionalInt leaderId,
        int epoch
    ) throws IOException {
        if (epoch < quorum.epoch()) {
            // We have a larger epoch, so the response is no longer relevant
            return Optional.of(true);
        } else if (error == Errors.FENCED_LEADER_EPOCH) {
            // The response indicates that the request had a stale epoch, but we need
            // to validate the epoch from the response against our current state.
            maybeBecomeFollower(leaderId, epoch);
            return Optional.of(true);
        } else if (error == Errors.BROKER_NOT_AVAILABLE) {
            if (quorum.isObserver()) {
                becomeUnattachedFollower(epoch);
                return Optional.of(true);
            } else {
                return Optional.of(false);
            }
        } else if (error == Errors.INCONSISTENT_GROUP_PROTOCOL) {
            // For now we treat this as a fatal error. Once we have support for quorum
            // reassignment, this error could suggest that either we or the recipient of
            // the request just has stale voter information, which means we can retry
            // after backing off.
            throw new IllegalStateException("Received error indicating inconsistent voter sets");
        } else if (error == Errors.INVALID_REQUEST) {
            throw new IllegalStateException("Received unexpected invalid request error");
        }

        return Optional.empty();
    }

    private void maybeBecomeFollower(
        OptionalInt leaderId,
        int epoch
    ) throws IOException {
        if (!hasConsistentLeader(epoch, leaderId)) {
            throw new IllegalStateException("Received request or response with leader " + leaderId +
                " and epoch " + epoch + " which is inconsistent with current leader " +
                quorum.leaderId() + " and epoch " + quorum.epoch());
        } else if (epoch > quorum.epoch()) {
            // If the request or response indicates a higher epoch, we bump our local
            // epoch and become a follower.
            becomeFollower(leaderId, epoch);
        } else if (leaderId.isPresent() && !quorum.hasLeader()) {
            // The request or response indicates the leader of the current epoch,
            // which is currently unknown
            becomeFetchingFollower(leaderId.getAsInt(), epoch);
        }
    }

    private boolean handleUnexpectedError(Errors error, RaftResponse.Inbound response) {
        logger.error("Unexpected error {} in {} response: {}",
            error, response.data.apiKey(), response);
        return false;
    }

    private void handleResponse(RaftResponse.Inbound response, long currentTimeMs) throws IOException {
        // The response epoch matches the local epoch, so we can handle the response
        ApiKeys apiKey = ApiKeys.forId(response.data.apiKey());
        final boolean handledSuccessfully;

        switch (apiKey) {
            case FETCH_QUORUM_RECORDS:
                handledSuccessfully = handleFetchQuorumRecordsResponse(response);
                break;

            case VOTE:
                handledSuccessfully = handleVoteResponse(response);
                break;

            case BEGIN_QUORUM_EPOCH:
                handledSuccessfully = handleBeginQuorumEpochResponse(response);
                break;

            case END_QUORUM_EPOCH:
                handledSuccessfully = handleEndQuorumEpochResponse(response);
                break;

            case FIND_QUORUM:
                handledSuccessfully = handleFindQuorumResponse(response);
                break;

            default:
                throw new IllegalArgumentException("Received unexpected response type: " + apiKey);
        }

        ConnectionState connection = connections.getOrCreate(response.sourceId());
        if (handledSuccessfully) {
            connection.onResponseReceived(response.correlationId, currentTimeMs);
        } else {
            connection.onResponseError(response.correlationId, currentTimeMs);
        }
    }

    /**
     * Validate a request which is only valid between voters. If an error is
     * present in the returned value, it should be returned in the response.
     */
    private Optional<Errors> validateVoterOnlyRequest(int remoteNodeId, int requestEpoch) {
        if (requestEpoch < quorum.epoch()) {
            return Optional.of(Errors.FENCED_LEADER_EPOCH);
        } else if (remoteNodeId < 0) {
            return Optional.of(Errors.INVALID_REQUEST);
        } else if (quorum.isObserver() || !quorum.isVoter(remoteNodeId)) {
            // Inconsistent voter state could be the result of an active
            // reassignment which has not been propagated to all of the
            // expected voters. We generally expect this to be a transient
            // error until all voters have replicated the reassignment record.
            return Optional.of(Errors.INCONSISTENT_VOTER_SET);
        } else {
            return Optional.empty();
        }
    }

    /**
     * Validate a request which is intended for the current quorum leader.
     * If an error is present in the returned value, it should be returned
     * in the response.
     */
    private Optional<Errors> validateLeaderOnlyRequest(int requestEpoch) {
        if (requestEpoch < quorum.epoch()) {
            return Optional.of(Errors.FENCED_LEADER_EPOCH);
        } else if (requestEpoch > quorum.epoch() || !quorum.isLeader()) {
            // If the epoch in the request is larger than our own epoch or
            // it matches our epoch and we are not a leader, then the request
            // is invalid. Unlike with Kafka topic partitions, leaders will
            // always be the first to know of their status.
            return Optional.of(Errors.INVALID_REQUEST);
        } else if (shutdown.get() != null) {
            return Optional.of(Errors.BROKER_NOT_AVAILABLE);
        } else {
            return Optional.empty();
        }
    }

    private void handleRequest(RaftRequest.Inbound request) throws IOException {
        ApiKeys apiKey = ApiKeys.forId(request.data.apiKey());
        final CompletableFuture<? extends ApiMessage> responseFuture;

        switch (apiKey) {
            case FETCH_QUORUM_RECORDS:
                responseFuture = handleFetchQuorumRecordsRequest(request);
                break;

            case VOTE:
                responseFuture = completedFuture(handleVoteRequest(request));
                break;

            case BEGIN_QUORUM_EPOCH:
                responseFuture = completedFuture(handleBeginQuorumEpochRequest(request));
                break;

            case END_QUORUM_EPOCH:
                responseFuture = completedFuture(handleEndQuorumEpochRequest(request));
                break;

            case FIND_QUORUM:
                responseFuture = completedFuture(handleFindQuorumRequest(request));
                break;

            case DESCRIBE_QUORUM:
                responseFuture = completedFuture(handleDescribeQuorumRequest(request));
                break;

            default:
                throw new IllegalArgumentException("Unexpected request type " + apiKey);
        }

        responseFuture.whenComplete((response, exception) -> {
            final ApiMessage message;
            if (response != null) {
                message = response;
            } else {
                message = RaftUtil.errorResponse(
                    apiKey,
                    Errors.forException(exception),
                    quorum.epoch(),
                    quorum.leaderId()
                );
            }
            sendOutboundMessage(new RaftResponse.Outbound(request.correlationId(), message));
        });
    }

    private void handleInboundMessage(RaftMessage message, long currentTimeMs) throws IOException {
        logger.trace("Received inbound message {}", message);

        if (message instanceof RaftRequest.Inbound) {
            RaftRequest.Inbound request = (RaftRequest.Inbound) message;
            handleRequest(request);
        } else if (message instanceof RaftResponse.Inbound) {
            RaftResponse.Inbound response = (RaftResponse.Inbound) message;
            handleResponse(response, currentTimeMs);
        } else {
            throw new IllegalArgumentException("Unexpected message " + message);
        }
    }

    private void sendOutboundMessage(RaftMessage message) {
        channel.send(message);
        logger.trace("Sent outbound message: {}", message);
    }

    private void maybeSendRequest(
        long currentTimeMs,
        int destinationId,
        Supplier<ApiMessage> requestData
    ) throws IOException {
        ConnectionState connection = connections.getOrCreate(destinationId);
        if (quorum.isObserver() && connection.hasRequestTimedOut(currentTimeMs)) {
            // Observers need to proactively find the leader if there is a request timeout
            becomeUnattachedFollower(quorum.epoch());
        } else if (connection.isReady(currentTimeMs)) {
            int correlationId = channel.newCorrelationId();
            ApiMessage request = requestData.get();
            sendOutboundMessage(new RaftRequest.Outbound(correlationId, request, destinationId, currentTimeMs));
            connection.onRequestSent(correlationId, time.milliseconds());
        } else {
            logger.trace("Connection {} is not ready for sending", connection);
        }
    }

    private EndQuorumEpochRequestData buildEndQuorumEpochRequest() {
        return new EndQuorumEpochRequestData()
                .setReplicaId(quorum.localId)
                .setLeaderId(quorum.leaderIdOrNil())
                .setLeaderEpoch(quorum.epoch())
                .setPreferredSuccessors(
                    quorum.leaderStateOrThrow().nonLeaderVotersByDescendingFetchOffset());
    }

    private void maybeSendEndQuorumEpoch(long currentTimeMs) throws IOException {
        for (Integer voterId : quorum.remoteVoters()) {
            maybeSendRequest(currentTimeMs, voterId, this::buildEndQuorumEpochRequest);
        }
    }

    private BeginQuorumEpochRequestData buildBeginQuorumEpochRequest() {
        return new BeginQuorumEpochRequestData()
                .setLeaderId(quorum.localId)
                .setLeaderEpoch(quorum.epoch());
    }

    private void maybeSendBeginQuorumEpochToFollowers(long currentTimeMs, LeaderState state) throws IOException {
        for (Integer followerId : state.nonEndorsingFollowers()) {
            maybeSendRequest(currentTimeMs, followerId, this::buildBeginQuorumEpochRequest);
        }
    }

    private VoteRequestData buildVoteRequest() {
        OffsetAndEpoch endOffset = endOffset();
        return VoteRequest.singletonRequest(
            log.topicPartition(),
            quorum.epoch(),
            quorum.localId,
            endOffset.epoch,
            endOffset.offset
        );
    }

    private void maybeSendVoteRequestToVoters(long currentTimeMs, CandidateState state) throws IOException {
        for (Integer voterId : state.unrecordedVoters()) {
            maybeSendRequest(currentTimeMs, voterId, this::buildVoteRequest);
        }
    }

    private FetchQuorumRecordsRequestData buildFetchQuorumRecordsRequest() {
        return new FetchQuorumRecordsRequestData()
            .setLeaderEpoch(quorum.epoch())
            .setReplicaId(quorum.localId)
            .setFetchOffset(log.endOffset().offset)
            .setLastFetchedEpoch(log.lastFetchedEpoch())
            .setMaxWaitTimeMs(fetchMaxWaitMs);
    }

    private void maybeSendFetchQuorumRecords(long currentTimeMs, int leaderId) throws IOException {
        maybeSendRequest(currentTimeMs, leaderId, this::buildFetchQuorumRecordsRequest);
    }

    private FindQuorumRequestData buildFindQuorumRequest() {
        return new FindQuorumRequestData()
            .setReplicaId(quorum.localId)
            .setHost(advertisedListener.getHostString())
            .setPort(advertisedListener.getPort())
            .setBootTimestamp(bootTimestamp);
    }

    private void maybeSendFindQuorum(long currentTimeMs) throws IOException {
        // Find a ready member of the quorum to send FindLeader to. Any voter can receive the
        // FindQuorum, but we only send one request at a time.
        OptionalInt readyNodeIdOpt = connections.findReadyBootstrapServer(currentTimeMs);
        if (readyNodeIdOpt.isPresent()) {
            maybeSendRequest(currentTimeMs, readyNodeIdOpt.getAsInt(), this::buildFindQuorumRequest);
        }
    }

    private void maybeSendRequests(long currentTimeMs) throws IOException {
        if (quorum.isLeader()) {
            LeaderState state = quorum.leaderStateOrThrow();
            maybeSendBeginQuorumEpochToFollowers(currentTimeMs, state);
            if (timer.isExpired()) {
                maybeSendFindQuorum(currentTimeMs);
            }
        } else if (quorum.isCandidate()) {
            CandidateState state = quorum.candidateStateOrThrow();
            maybeSendVoteRequestToVoters(currentTimeMs, state);
        } else {
            FollowerState state = quorum.followerStateOrThrow();
            if (quorum.isObserver() && (!state.hasLeader() || timer.isExpired())) {
                maybeSendFindQuorum(currentTimeMs);
            } else if (state.hasLeader()) {
                int leaderId = state.leaderId();
                maybeSendFetchQuorumRecords(currentTimeMs, leaderId);
            }
        }

        if (connections.hasUnknownVoterEndpoints()) {
            maybeSendFindQuorum(currentTimeMs);
        }
    }

    public boolean isRunning() {
        GracefulShutdown gracefulShutdown = shutdown.get();
        return gracefulShutdown == null || !gracefulShutdown.isFinished();
    }

    public boolean isShuttingDown() {
        GracefulShutdown gracefulShutdown = shutdown.get();
        return gracefulShutdown != null && !gracefulShutdown.isFinished();
    }

    private void pollShutdown(GracefulShutdown shutdown) throws IOException {
        // Graceful shutdown allows a leader or candidate to resign its leadership without
        // awaiting expiration of the election timeout. As soon as another leader is elected,
        // the shutdown is considered complete.

        shutdown.update();
        if (shutdown.isFinished()) {
            return;
        }

        long currentTimeMs = shutdown.finishTimer.currentTimeMs();

        if (quorum.remoteVoters().isEmpty() || quorum.hasRemoteLeader()) {
            shutdown.complete();
            return;
        } else if (quorum.isLeader()) {
            maybeSendEndQuorumEpoch(currentTimeMs);
        }

        List<RaftMessage> inboundMessages = channel.receive(shutdown.finishTimer.remainingMs());
        for (RaftMessage message : inboundMessages)
            handleInboundMessage(message, currentTimeMs);
    }

    public void poll() throws IOException {
        GracefulShutdown gracefulShutdown = shutdown.get();
        if (gracefulShutdown != null) {
            pollShutdown(gracefulShutdown);
        } else {
            timer.update();

            if (quorum.isVoter() && !quorum.isLeader() && timer.isExpired()) {
                logger.debug("Become candidate due to fetch timeout");
                becomeCandidate();
            } else if (quorum.isCandidate() && timer.isExpired()) {
                CandidateState state = quorum.candidateStateOrThrow();
                if (state.isBackingOff()) {
                    logger.debug("Re-elect as candidate after election backoff has completed");
                    becomeCandidate();
                } else {
                    logger.debug("Election has timed out, backing off before re-elect again");
                    state.startBackingOff();
                    timer.reset(binaryExponentialElectionBackoffMs(state.retries()));
                }
            }

            timer.update();
            long currentTimeMs = timer.currentTimeMs();

            maybeSendRequests(currentTimeMs);
            handlePendingAppends(currentTimeMs);

            timer.update();
            currentTimeMs = timer.currentTimeMs();
            kafkaRaftMetrics.updatePollStart(currentTimeMs);
            // TODO: Receive time needs to take into account backing off operations that still need doing
            List<RaftMessage> inboundMessages = channel.receive(timer.remainingMs());
            timer.update();
            currentTimeMs = timer.currentTimeMs();
            kafkaRaftMetrics.updatePollEnd(currentTimeMs);

            for (RaftMessage message : inboundMessages)
                handleInboundMessage(message, currentTimeMs);
        }
    }

    private void handlePendingAppends(long currentTimeMs) {
        UnwrittenAppend unwrittenAppend = unwrittenAppends.poll();
        if (unwrittenAppend == null || unwrittenAppend.isCancelled())
            return;

        if (quorum.isLeader()) {
            if (unwrittenAppend.isTimedOut(currentTimeMs)) {
                unwrittenAppend.fail(new TimeoutException());
            } else {
                LeaderState leaderState = quorum.leaderStateOrThrow();
                int epoch = quorum.epoch();
                LogAppendInfo info = maybeAppendAsLeader(leaderState, unwrittenAppend.records);
                if (info != null) {
                    OffsetAndEpoch offsetAndEpoch = new OffsetAndEpoch(info.lastOffset, epoch);
                    unwrittenAppend.complete(offsetAndEpoch);
                    uncommittedAppends.put(offsetAndEpoch, new AppendBatchAndTime(info.lastOffset - info.firstOffset + 1, currentTimeMs));
                } else {
                    unwrittenAppend.fail(new InvalidRequestException("Leader refused the append"));
                }
            }
        } else {
            unwrittenAppend.fail(new NotLeaderOrFollowerException(
                "Append refused since this node is no longer the leader"));
        }
    }

    /**
     * Append a set of records to the log. Successful completion of the future indicates a success of
     * the append, with the uncommitted base offset and epoch.
     *
     * @param records The records to write to the log
     * @return The uncommitted base offset and epoch of the appended records
     */
    private CompletableFuture<OffsetAndEpoch> append(Records records) {
        if (records.sizeInBytes() == 0)
            throw new IllegalArgumentException("Attempt to append empty record set");

        if (shutdown.get() != null)
            throw new IllegalStateException("Cannot append records while we are shutting down");

        CompletableFuture<OffsetAndEpoch> future = new CompletableFuture<>();
        UnwrittenAppend unwrittenAppend = new UnwrittenAppend(
            records, future, time.milliseconds(), requestTimeoutMs);

        if (!unwrittenAppends.offer(unwrittenAppend)) {
            future.completeExceptionally(new KafkaException("Failed to append records since the unsent " +
                "append queue is full"));
        }
        channel.wakeup();
        return future;
    }

    /**
     * Read from the local log. This will only return records which have been committed to the quorum.
     * @param offsetAndEpoch The first offset to read from and the previous consumed epoch
     * @param highWatermark The current high watermark
     * @return A set of records beginning at the request offset
     */
    private Records readCommitted(OffsetAndEpoch offsetAndEpoch, long highWatermark) {
        Optional<OffsetAndEpoch> endOffset = log.endOffsetForEpoch(offsetAndEpoch.epoch);
        if (!endOffset.isPresent() || offsetAndEpoch.offset > endOffset.get().offset) {
            throw new LogTruncationException("The requested offset and epoch " + offsetAndEpoch +
                    " are not in range. The closest offset we found is " + endOffset + ".");
        }
        return log.read(offsetAndEpoch.offset, OptionalLong.of(highWatermark)).records;
    }

    @Override
    public CompletableFuture<Void> shutdown(int timeoutMs) {
        logger.info("Beginning graceful shutdown");
        CompletableFuture<Void> shutdownComplete = new CompletableFuture<>();
        shutdown.set(new GracefulShutdown(timeoutMs, shutdownComplete));
        channel.wakeup();
        return shutdownComplete;
    }

    private void close() {
        kafkaRaftMetrics.close();
    }

    public OptionalLong highWatermark() {
        return quorum.highWatermark().isPresent() ? OptionalLong.of(quorum.highWatermark().get().offset) : OptionalLong.empty();
    }

    private class GracefulShutdown {
        final Timer finishTimer;
        final CompletableFuture<Void> completeFuture;

        public GracefulShutdown(long shutdownTimeoutMs,
                                CompletableFuture<Void> completeFuture) {
            this.finishTimer = time.timer(shutdownTimeoutMs);
            this.completeFuture = completeFuture;
        }

        public void update() {
            finishTimer.update();
            if (finishTimer.isExpired()) {
                close();
                logger.warn("Graceful shutdown timed out after {}ms", timer.timeoutMs());
                completeFuture.completeExceptionally(
                    new TimeoutException("Timeout expired before shutdown completed"));
            }
        }

        public boolean isFinished() {
            return completeFuture.isDone();
        }

        public boolean succeeded() {
            return isFinished() && !failed();
        }

        public boolean failed() {
            return completeFuture.isCompletedExceptionally();
        }

        public void complete() {
            close();
            logger.info("Graceful shutdown completed");
            completeFuture.complete(null);
        }
    }

    private static class UnwrittenAppend {
        private final Records records;
        private final CompletableFuture<OffsetAndEpoch> future;
        private final long createTimeMs;
        private final long requestTimeoutMs;

        private UnwrittenAppend(Records records,
                                CompletableFuture<OffsetAndEpoch> future,
                                long createTimeMs,
                                long requestTimeoutMs) {
            this.records = records;
            this.future = future;
            this.createTimeMs = createTimeMs;
            this.requestTimeoutMs = requestTimeoutMs;
        }

        public void complete(OffsetAndEpoch offsetAndEpoch) {
            future.complete(offsetAndEpoch);
        }

        public void fail(Exception e) {
            future.completeExceptionally(e);
        }

        public boolean isTimedOut(long currentTimeMs) {
            return currentTimeMs > createTimeMs + requestTimeoutMs;
        }

        public boolean isCancelled() {
            return future.isCancelled();
        }
    }

    private static class AppendBatchAndTime {
        private final long numRecords;
        private final long appendTimeMs;

        private AppendBatchAndTime(long numRecords, long appendTimeMs) {
            if (numRecords <= 0) {
                throw new IllegalArgumentException("Number of records appended in this batch should always be positive");
            }

            this.appendTimeMs = appendTimeMs;
            this.numRecords = numRecords;
        }
    }
}