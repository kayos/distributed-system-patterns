package replicate.paxoslog;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import replicate.common.*;
import replicate.net.InetAddressAndPort;
import replicate.net.requestwaitinglist.RequestWaitingList;
import replicate.paxos.PaxosState;
import replicate.paxos.messages.CommitResponse;
import replicate.paxos.messages.GetValueResponse;
import replicate.paxos.messages.ProposalResponse;
import replicate.paxoslog.messages.CommitRequest;
import replicate.paxoslog.messages.PrepareRequest;
import replicate.paxoslog.messages.PrepareResponse;
import replicate.paxoslog.messages.ProposalRequest;
import replicate.quorum.messages.GetValueRequest;
import replicate.twophaseexecution.CompareAndSwap;
import replicate.twophaseexecution.messages.ExecuteCommandRequest;
import replicate.twophaseexecution.messages.ExecuteCommandResponse;
import replicate.vsr.CompletionCallback;
import replicate.wal.Command;
import replicate.wal.SetValueCommand;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class PaxosLog extends Replica {
    private static Logger logger = LogManager.getLogger(PaxosLog.class);

    //Paxos State
    //THIS HAS TO BE DURABLE.. Homework. Make paxosLog durable.
    //Solution: Use DurableKVStore instead of a TreeMap
    Map<Integer, PaxosState> paxosLog = new TreeMap<>(); //RocksDB

    //This is the final state for Key-value store.
    Map<String, String> kv = new HashMap<>();

    private final SetValueCommand NO_OP_COMMAND = new SetValueCommand("", "");
    int serverId;
    RequestWaitingList requestWaitingList;
    public PaxosLog(String name, SystemClock clock, Config config, InetAddressAndPort clientAddress, InetAddressAndPort peerConnectionAddress, List<InetAddressAndPort> peers) throws IOException {
        super(name, config, clock, clientAddress, peerConnectionAddress, peers);
        this.serverId = config.getServerId();
        requestWaitingList = new RequestWaitingList(clock);
    }


    @Override
    protected void registerHandlers() {
        //client rpc
        handlesRequestAsync(MessageId.GetValueRequest, this::handleClientGetValueRequest, GetValueRequest.class);
        handlesRequestAsync(MessageId.ExcuteCommandRequest, this::handleClientExecuteCommand, ExecuteCommandRequest.class);

        //peer to peer message passing
        handlesMessage(MessageId.Prepare, this::handlePrepare, PrepareRequest.class);
        handlesMessage(MessageId.Promise, this::handlePromise, PrepareResponse.class);

        handlesMessage(MessageId.ProposeRequest, this::handlePaxosProposal, ProposalRequest.class);
        handlesMessage(MessageId.ProposeResponse, this::handleProposalResponse, ProposalResponse.class);

        handlesMessage(MessageId.Commit, this::handlePaxosCommit, CommitRequest.class);
        handlesMessage(MessageId.CommitResponse, this::handleCommitResponse, CommitResponse.class);
    }

    private void handleCommitResponse(Message<CommitResponse> commitResponseMessage) {
        handleResponse(commitResponseMessage);
    }

    private void handleProposalResponse(Message<ProposalResponse> proposalResponseMessage) {
        handleResponse(proposalResponseMessage);
    }

    private void handlePromise(Message<PrepareResponse> prepareResponseMessage) {
        handleResponse(prepareResponseMessage);
    }

    private CompletableFuture<ExecuteCommandResponse> handleClientExecuteCommand(ExecuteCommandRequest t) {
        var commitCallback = new CompletionCallback<ExecuteCommandResponse>();
        //tricky. Always start with index zero, because you might have
        // incomplete runs at lower indexes. If the entries below the index
        //at which we can commit the provided request are not committed,
        //we won't be able to execute the request as high-watermark is not
        // reached. Not very suitable in practice.
        CompletableFuture<PaxosResult> appendFuture = append(0,
                t.command, commitCallback);

        return appendFuture.thenCompose(f -> commitCallback.getFuture());
    }


    private CompletableFuture<GetValueResponse> handleClientGetValueRequest(GetValueRequest request) {
        var commitCallback = new CompletionCallback<ExecuteCommandResponse>();
        var appendFuture = append(0, NO_OP_COMMAND.serialize(),
                commitCallback);
        return appendFuture
                .thenCompose(f ->
                        commitCallback.getFuture()
                                .thenApply(r -> new GetValueResponse(Optional.ofNullable(kv.get(request.getKey())))));
    }



    AtomicInteger maxKnownPaxosRoundId = new AtomicInteger(1);
    AtomicInteger logIndex = new AtomicInteger(0);

    public CompletableFuture<PaxosResult> append(int index, byte[] clientRequest, CompletionCallback<ExecuteCommandResponse> callback) {
        CompletableFuture<PaxosResult> appendFuture = doPaxos(index, clientRequest, callback);
        return appendFuture.thenCompose((result)->{
           if (result.value.stream().allMatch(v -> v != clientRequest)) {
               logger.info("Could not append proposed value to " + logIndex + ". Trying next index");
               return append(logIndex.incrementAndGet(), clientRequest, callback);
           }
           return CompletableFuture.completedFuture(result);
        });
    }

    private CompletableFuture<PaxosResult> doPaxos(int index, byte[] value, CompletionCallback<ExecuteCommandResponse> callback) {
        int maxAttempts = 5;
        return FutureUtils.retryWithRandomDelay(() -> {
            //Each retry with higher generation/epoch
            MonotonicId newGeneration = new MonotonicId(maxKnownPaxosRoundId.incrementAndGet(),
                    serverId);
            CompletableFuture<PaxosResult> result = doPaxos(newGeneration,
                    index, value, callback);
            return result;
        }, maxAttempts, singularUpdateQueueExecutor);
    }

    private CompletableFuture<PaxosResult> doPaxos(MonotonicId monotonicId, int index, byte[] initialValue, CompletionCallback<ExecuteCommandResponse> callback) {
        return sendPrepareRequest(index, monotonicId).
                thenCompose((result) -> {
                    byte[] proposedValue = getProposalValue(index, initialValue, result.values());
                    logger.debug(getName() + " proposing " + Command.deserialize(proposedValue) + " for index " + index + " Initial value is " + Command.deserialize(initialValue));
                    return sendProposeRequest(index, proposedValue, monotonicId);

                }).thenCompose(proposedValue -> {
                    //Once the index at which the command is committed
                    // reaches 'high-watermark', return the result.
                    if (proposedValue == initialValue) {
                        requestWaitingList.add(index, callback);
                    }
                    return sendCommitRequest(index, proposedValue, monotonicId)
                            .thenApply(r -> new PaxosResult(Optional.of(proposedValue), true));
                });
    }


    private byte[] getProposalValue(int index, byte[] initialValue, Collection<PrepareResponse> promises) {
        logger.debug(getName() + " got promises " + promises + " for index " + index);
        var mostRecentAcceptedValue = getMostRecentAcceptedValue(promises);
        return mostRecentAcceptedValue.acceptedValue.orElse(initialValue);
    }

    private PrepareResponse getMostRecentAcceptedValue(Collection<PrepareResponse> prepareResponses) {
        return prepareResponses.stream().max(Comparator.comparing(r -> r.acceptedGeneration.orElse(MonotonicId.empty()))).get();
    }

    private CompletableFuture<Boolean> sendCommitRequest(int index, byte[] value, MonotonicId monotonicId) {
        AsyncQuorumCallback<CommitResponse> commitCallback = new AsyncQuorumCallback<CommitResponse>(getNoOfReplicas(), c -> c.success);
        logger.info(getName() + " sending commit request for " + index);
        sendMessageToReplicas(commitCallback, MessageId.Commit, new CommitRequest(index, value, monotonicId));
        return commitCallback.getQuorumFuture().thenApply(result -> true);
    }


    private CompletableFuture<byte[]> sendProposeRequest(int index, byte[] proposedValue, MonotonicId monotonicId) {
        var proposalCallback = new AsyncQuorumCallback<ProposalResponse>(getNoOfReplicas(), p -> p.success);
        sendMessageToReplicas(proposalCallback, MessageId.ProposeRequest, new ProposalRequest(monotonicId, index, proposedValue));
        return proposalCallback.getQuorumFuture().thenApply(r -> proposedValue);
    }

    private CompletableFuture<Map<InetAddressAndPort, PrepareResponse>> sendPrepareRequest(int index, MonotonicId monotonicId) {
        var callback = new AsyncQuorumCallback<PrepareResponse>(getNoOfReplicas(), p -> p.promised);
        logger.info(getName() + " sending prepare request for " + index);
        sendMessageToReplicas(callback, MessageId.Prepare, new PrepareRequest(index, monotonicId));
        return callback.getQuorumFuture();
    }

    private void handlePaxosCommit(Message<CommitRequest> message) {
        var request = message.messagePayload();
        var paxosState = getOrCreatePaxosState(request.index);
        //Because commit is invoked only after successful prepare and propose. accept a commit message
        var committedPaxosState = paxosState.commit(request.generation, Optional.ofNullable(request.committedValue));
        paxosLog.put(request.index, committedPaxosState);

        applyIfAllPreviousEntriesAreCommitted(request);
        sendOneway(message.getFromAddress(), new CommitResponse(true), message.getCorrelationId());
    }

    private void applyIfAllPreviousEntriesAreCommitted(CommitRequest commitRequest) {
        //if all entries upto logIndex - 1 are committed, apply this entry.
        if (allPreviousEntriesCommitted(commitRequest)) {
            addAndApply(commitRequest.index, commitRequest.committedValue);
        }

        //see if there are entries above this logIndex which are commited, apply those entries.
        applySubsequentEntries(commitRequest);
    }

    private void applySubsequentEntries(CommitRequest commitRequest) {
        for(int commitIndex = commitRequest.index + 1; ; commitIndex++) {
            var paxosState = paxosLog.get(commitIndex);
            if (paxosState == null) {
                break;
            }
            var committed = paxosState.committedValue().get();
            addAndApply(commitIndex, committed);
        }
    }

    private boolean allPreviousEntriesCommitted(CommitRequest commitRequest) {
        var previousIndexes = this.paxosLog.keySet().stream().filter(index -> index < commitRequest.index).collect(Collectors.toList());
        var allPreviousCommitted = true;
        for (Integer previousIndex : previousIndexes) {
            if (paxosLog.get(previousIndex).committedValue().isEmpty()) {
                allPreviousCommitted = false;
                break;
            }
        }
        return allPreviousCommitted;
    }

    private void addAndApply(int index, byte[] logEntry) {
        var command = Command.deserialize(logEntry);
        if (command instanceof SetValueCommand) {
            SetValueCommand setValueCommand = (SetValueCommand)command;
            kv.put(setValueCommand.getKey(), setValueCommand.getValue());
            requestWaitingList.handleResponse(index, new ExecuteCommandResponse(Optional.of(setValueCommand.getValue()), true));

        } else if (command instanceof CompareAndSwap) {
            CompareAndSwap cas = (CompareAndSwap)command;
            Optional<String> existingValue = Optional.ofNullable(kv.get(cas.getKey()));
            if (existingValue.equals(cas.getExistingValue())) {
                kv.put(cas.getKey(), cas.getNewValue());
                requestWaitingList.handleResponse(index,  new ExecuteCommandResponse(existingValue, true));
            }
            requestWaitingList.handleResponse(index,  new ExecuteCommandResponse(existingValue, false));
        }
    }

    private void handlePaxosProposal(Message<ProposalRequest> message) {
        var request = message.messagePayload();
        var generation = request.generation;
        var paxosState = getOrCreatePaxosState(request.index);
        var accepted = false;
        if (paxosState.canAccept(generation)) {
            var acceptedPaxosState = paxosState.accept(generation, Optional.ofNullable(request.proposedValue));
            paxosLog.put(request.index, acceptedPaxosState);
            accepted = true;
        }

        logger.info(getName() + (accepted?" accepting ":"rejecting ") + "proposal "  + request.generation + " as paxosState.promisedGeneration=" + paxosState.promisedGeneration());
        sendOneway(message.getFromAddress(), new ProposalResponse(accepted), message.getCorrelationId());
    }


    public void handlePrepare(Message<PrepareRequest> message) {
        var request = message.messagePayload();
        var paxosState = getOrCreatePaxosState(request.index);
        boolean promised = false;
        if (paxosState.canPromise(request.generation)) {
            PaxosState promisedPaxosState = paxosState.promise(request.generation);
            paxosLog.put(request.index, promisedPaxosState);
            promised = true;
        }
        sendOneway(message.getFromAddress(), new PrepareResponse(promised, paxosState.acceptedValue(), paxosState.acceptedGeneration()), message.getCorrelationId());
    }

    private PaxosState getOrCreatePaxosState(int index) {
        var paxosState = paxosLog.get(index);
        if (paxosState == null) {
            paxosState = new PaxosState();
            paxosLog.put(index, paxosState);
        }
        return paxosState;
   }

    public String getValue(String title) {
        return kv.get(title);
    }
}
