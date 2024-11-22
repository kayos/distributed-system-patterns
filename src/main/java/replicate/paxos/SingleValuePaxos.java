package replicate.paxos;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import replicate.common.*;
import replicate.net.InetAddressAndPort;
import replicate.paxos.messages.*;
import replicate.quorum.messages.GetValueRequest;
import replicate.quorum.messages.SetValueRequest;
import replicate.quorum.messages.SetValueResponse;
import replicate.twophaseexecution.CompareAndSwap;
import replicate.wal.Command;
import replicate.wal.SetValueCommand;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Paxos operates in three phases
 * Prepare      : To order the request by assigning a unique epoch/generation number
 * And to know about accepted values/Commands in previous quorum.
 * Propose      : Propose a new/selected value to all the nodes.
 * Commit(Learn): Tell all the nodes about the value/command accepted by majority quorum.
 * Once committed the value can be returned to the user.
 * <p>
 * +-------+         +--------+                 +-------+         +------+
 * |       |         |        |                 |       |         |      |
 * |Client |         |node1   |                 | node2 |         | node3|
 * |       |         |        |                 |       |         |      |
 * +---+---+         +----+---+                 +---+---+         +--+---+
 * |   command        |                         |                |
 * +------------------>                         |                |
 * |                  +---+                     |                |
 * |                  <---|     Prepare         |                |
 * |                  +------------------------>+                |
 * |                  | <-----------------------|                |
 * |                  +----------------------------------------->+
 * |                  <------------------------------------------+
 * |                  |-----|                   |                |
 * |                  <-----|   Propose         |                |
 * |                  +------------------------>+               |
 * |                  |<------------------------|                |
 * |                  +----------------------------------------->+
 * |                  +<-----------------------------------------+
 * |                  |                         |                |
 * |                  +------+                  |                |
 * |                  |      |  Commit          |                |
 * |                  <------+  Execute         |                |
 * |                  |------------------------>+                |
 * |   Result         +----------------------------------------> ++
 * +<-----------------+                                          |
 * |                  |                                          |
 * +                  +                                          +
 */

public class SingleValuePaxos extends Replica {
    private static Logger logger = LogManager.getLogger(SingleValuePaxos.class);
    public PaxosState paxosState = new PaxosState();
    //this has to be made durable.
    //DurableKVStore to store paxos state.

    AtomicInteger lastKnownGeneration = new AtomicInteger(0);
    int serverId;

    public SingleValuePaxos(String name, SystemClock clock, Config config, InetAddressAndPort clientAddress, InetAddressAndPort peerConnectionAddress, List<InetAddressAndPort> peers) throws IOException {
        super(name, config, clock, clientAddress, peerConnectionAddress, peers);
        this.serverId = config.getServerId();
    }

    @Override
    protected void registerHandlers() {
        //client rpc
        //support executeCommand request just like we saw in TwoPhaseExecution.
        handlesRequestAsync(MessageId.SetValueRequest, this::handleSetValueRequest, SetValueRequest.class);
        handlesRequestAsync(MessageId.GetValueRequest, this::handleGetValueRequest, GetValueRequest.class);

        //peer to peer message passing
        handlesMessage(MessageId.Prepare, this::handlePrepare, PrepareRequest.class);
        handlesMessage(MessageId.Promise, this::handlePromise, PrepareResponse.class);

        handlesMessage(MessageId.ProposeRequest, this::handleProposal, ProposalRequest.class);
        handlesMessage(MessageId.ProposeResponse, this::handleProposalResponse, ProposalResponse.class);

        handlesMessage(MessageId.Commit, this::handleCommit, CommitRequest.class);
        handlesMessage(MessageId.CommitResponse, this::handleCommitResponse, CommitResponse.class);
    }

    private void handleCommitResponse(Message<CommitResponse> commitResponseMessage) {
        handleResponse(commitResponseMessage);
    }

    private void handleProposalResponse(Message<ProposalResponse> proposalResponseMessage) {
        handleResponse(proposalResponseMessage);
    }

    private void handlePromise(Message<PrepareResponse> promise) {
        handleResponse(promise);
    }

    //TODO: handle execute request.
    private CompletableFuture<SetValueResponse> handleSetValueRequest(SetValueRequest setValueRequest) {
        return doPaxos(new SetValueCommand(setValueRequest.getKey(), setValueRequest.getValue()).serialize()).thenApply(value -> new SetValueResponse(value.orElse("")));
    }

    private CompletableFuture<GetValueResponse> handleGetValueRequest(GetValueRequest getValueRequest) {
        return doPaxos(null).thenApply(value -> new GetValueResponse(value));
    }

    private void handleCommit(Message<CommitRequest> message) {
        var req = message.messagePayload();
        boolean committed = false;
        if (paxosState.canAccept(req.generation)) {
            logger.info("Accepting commit for " + req.value + "promisedGeneration=" + paxosState.promisedGeneration() + " req generation=" + req.generation);
            this.paxosState = paxosState.commit(req.generation, Optional.ofNullable(req.value));
            committed = true;
        }
        sendOneway(message.getFromAddress(), new CommitResponse(committed), message.getCorrelationId());
    }

    private CompletableFuture<Optional<String>> doPaxos(byte[] value) {
        int maxAttempts = 2;
        return FutureUtils.retryWithRandomDelay(() -> {
            //Each retry with higher generation/epoch
            MonotonicId newGeneration = new MonotonicId(lastKnownGeneration.incrementAndGet(),
                    serverId);
            return doPaxos(newGeneration, value);
        }, maxAttempts, singularUpdateQueueExecutor).thenApply(result -> result.value);

    }

    SetValueCommand getAcceptedCommand() {
        return (SetValueCommand) Command.deserialize(paxosState.acceptedValue().get());
    }

    public static class PaxosResult {
        public final Optional<String> value;
        public final boolean success;

        public PaxosResult(Optional<String> value, boolean success) {
            this.value = value;
            this.success = success;
        }
    }

    private CompletableFuture<PaxosResult> doPaxos(MonotonicId newGeneration, byte[] value) {
        logger.info(getName() + ": Sending Prepare with " + newGeneration);
        return prepare(newGeneration)
                .thenCompose((result) ->
                        proposeValue(newGeneration, value, result))
                .thenCompose(acceptedValue ->
                        commitValue(newGeneration, acceptedValue));
    }

    private CompletableFuture<PaxosResult> commitValue(MonotonicId monotonicId, byte[] acceptedValue) {
        logger.info(getName() + ": Committing value " + acceptedValue + " for generation " + monotonicId);
        return sendCommitRequest(monotonicId, acceptedValue)
                .thenApply(r -> {
                    String result = executeCommand(acceptedValue);
                    return new PaxosResult(Optional.ofNullable(result), true);
                });
    }

    private CompletableFuture<byte[]> proposeValue(MonotonicId acceptedGeneration, byte[] value, Map<InetAddressAndPort, PrepareResponse> result) {
        byte[] proposedValue = getProposalValue(value, result.values());
        logger.info(getName() + ": Proposing " + proposedValue + " for generation " + acceptedGeneration);
        return sendProposeRequest(proposedValue, acceptedGeneration);
    }

    private Map<String, String> kv = new HashMap<>();

    private String executeCommand(byte[] acceptedValue) {
        if (acceptedValue == null) {
            return null;
        }

        Command command = Command.deserialize(new ByteArrayInputStream(acceptedValue));
        if (command instanceof SetValueCommand setValueCommand) {
            return handleSetValueCommand(setValueCommand);

        } else if (command instanceof CompareAndSwap compareAndSwap) {
            //execute cas.
        } //register lease // hold lock// transfer balance...
        throw new IllegalArgumentException("Unknown command to execute");
    }

    private String handleSetValueCommand(SetValueCommand setValueCommand) {
        kv.put(setValueCommand.getKey(), setValueCommand.getValue());
        return setValueCommand.getValue();
    }


    private byte[] getProposalValue(byte[] initialValue, Collection<PrepareResponse> promises) {
        PrepareResponse mostRecentAcceptedValue = getMostRecentAcceptedValue(promises);
        logger.debug("Most Recent promise " + mostRecentAcceptedValue);
        return mostRecentAcceptedValue.acceptedValue.orElse(initialValue);
    }

    private PrepareResponse getMostRecentAcceptedValue(Collection<PrepareResponse> prepareResponses) {
        logger.debug("Picking up values from " + prepareResponses);
        return prepareResponses.stream().max(Comparator.comparing(r -> r.acceptedBallot.orElse(MonotonicId.empty()))).get();
    }

    private CompletableFuture<Boolean> sendCommitRequest(MonotonicId monotonicId, byte[] value) {
        AsyncQuorumCallback<CommitResponse> commitCallback = new AsyncQuorumCallback<CommitResponse>(getNoOfReplicas(), c -> c.success);
        sendMessageToReplicas(commitCallback, MessageId.Commit, new CommitRequest(monotonicId, value));
        return commitCallback.getQuorumFuture().thenApply(r -> true);
    }

    private CompletableFuture<byte[]> sendProposeRequest(byte[] proposedValue, MonotonicId monotonicId) {
        AsyncQuorumCallback<ProposalResponse> proposalCallback = new AsyncQuorumCallback<ProposalResponse>(getNoOfReplicas(), p -> p.success);
        sendMessageToReplicas(proposalCallback, MessageId.ProposeRequest, new ProposalRequest(monotonicId, proposedValue));
        return proposalCallback.getQuorumFuture().thenApply(result -> proposedValue);
    }

    private CompletableFuture<Map<InetAddressAndPort, PrepareResponse>> prepare(MonotonicId monotonicId) {
        var callback = new AsyncQuorumCallback<PrepareResponse>(getNoOfReplicas(), p -> p.promised);
        sendMessageToReplicas(callback, MessageId.Prepare, new PrepareRequest(monotonicId));
        return callback.getQuorumFuture();
    }

    private void handleProposal(Message<ProposalRequest> message) {
        var request = message.messagePayload();
        MonotonicId ballot = request.monotonicId;
        boolean accepted =  false;
        if (paxosState.canAccept(ballot)) {
            this.paxosState = paxosState.accept(ballot, Optional.ofNullable(request.proposedValue));
            accepted = true;
        }
        sendOneway(message.getFromAddress(), new ProposalResponse(accepted), message.getCorrelationId());
    }

    public void handlePrepare(Message<PrepareRequest> message) {
        var prepareRequest = message.messagePayload();
        MonotonicId generation = prepareRequest.monotonicId;
        if (paxosState.promisedGeneration().isAfter(generation)) {
            sendOneway(message.getFromAddress(), new PrepareResponse(false, paxosState.acceptedValue(), paxosState.acceptedGeneration()), message.getCorrelationId());
        } else {
            paxosState = paxosState.promise(generation);
            sendOneway(message.getFromAddress(), new PrepareResponse(true, paxosState.acceptedValue(), paxosState.acceptedGeneration()), message.getCorrelationId());
        }
    }
}
