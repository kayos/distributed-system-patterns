package replicate.vsr;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import replicate.common.*;
import replicate.net.InetAddressAndPort;
import replicate.net.requestwaitinglist.RequestWaitingList;
import replicate.twophaseexecution.CompareAndSwap;
import replicate.twophaseexecution.messages.ExecuteCommandRequest;
import replicate.twophaseexecution.messages.ExecuteCommandResponse;
import replicate.vsr.messages.*;
import replicate.wal.Command;
import replicate.wal.SetValueCommand;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ViewStampedReplication extends Replica {
    private static final Logger logger = LogManager.getLogger(ViewStampedReplication.class);

    @Override
    protected void sendHeartbeats() {
        logger.info(getName() + " sending heartbeat message");
        sendOnewayMessageToOtherReplicas(new Commit(viewNumber, commitNumber));
    }

    @Override
    protected void checkLeader() {
        checkPrimary();
    }

    private void checkPrimary() {
        logger.info(getName() + " checking heartbeat status at " + clock.nanoTime());
        Duration timeSinceLastHeartbeat = elapsedTimeSinceLastHeartbeat();
        if (timeSinceLastHeartbeat.compareTo(heartbeatTimeout) > 0) {
            singularUpdateQueueExecutor.submit(()-> {
                logger.info(getName() + " heartbeat timedOut after " + timeSinceLastHeartbeat.toMillis() + "ms");
                resetMessageCounters();
                transitionToViewChange();
            });
        }
    }

    private void resetMessageCounters() {
        startViewChangeCounter = 0;
        doViewChangeCounter = 0;
    }

    private void transitionToViewChange() {
        logger.info(getName() + " Triggering view change from view " + viewNumber + " to " + (viewNumber + 1));
        heartBeatScheduler.stop();
        this.status = Status.ViewChange;
        viewNumber = viewNumber + 1;
        sendOnewayMessageToReplicas(new StartViewChange(MessageId.StartViewChange, viewNumber, getReplicaIndex()));
    }

    public InetAddressAndPort getPrimaryAddress() {
        return configuration.getPrimaryForView(viewNumber);
    }

    public int getViewNumber() {
        return viewNumber;
    }

    static enum Status {
        Normal,ViewChange,Recovering
    }

    private Map<Integer, LogEntry> log = new HashMap<>();

    private int viewNumber = 0;
    private Status status = Status.Normal; //Change based on the stored state on disk.
    private final Configuration configuration;
    private int opNumber = 0;
    private int commitNumber = 0;
    private int startViewChangeCounter;
    private int doViewChangeCounter;

    private int normalStatusViewNumber = viewNumber;

    private RequestWaitingList pendingRquests;

    public ViewStampedReplication(String name, Config config, SystemClock clock, InetAddressAndPort clientConnectionAddress, InetAddressAndPort peerConnectionAddress, List<InetAddressAndPort> peerAddresses) throws IOException {
        super(name, config, clock, clientConnectionAddress, peerConnectionAddress, peerAddresses);
        this.configuration = new Configuration(peerAddresses);
        pendingRquests = new RequestWaitingList(clock, Duration.ofMillis(1000));
    }

    @Override
    public void onStart() {
        if (isPrimary()) {
            heartBeatScheduler.start(); //start sending heartbeats
        } else {
            heartbeatChecker.start();
        }
    }

    @Override
    protected void registerHandlers() {
        //client interface rpc
        handlesRequestAsync(MessageId.ExcuteCommandRequest, this::handleClientRequest, ExecuteCommandRequest.class);

        //peers communicate by message passing.
        handlesMessage(MessageId.Prepare, this::handlePrepare, Prepare.class);
        handlesMessage(MessageId.PrepareOK, this::handlePrepareOk, PrepareOK.class);
        handlesMessage(MessageId.Commit, this::handleCommit, Commit.class);
        handlesMessage(MessageId.StartViewChange, this::handleStartViewChange, StartViewChange.class);
        handlesMessage(MessageId.DoViewChange, this::handleDoViewChange, DoViewChange.class);
        handlesMessage(MessageId.StartView, this::handleStartView, StartView.class);
    }

    private void handleStartView(Message<StartView> message) {
        var startView = message.messagePayload();
        logger.info(getName()  + " starting view " + this.viewNumber);
        this.log = startView.log;
        this.opNumber = startView.opNumber;
        this.commitNumber = startView.commitNumber;
        this.status = Status.Normal;
        this.normalStatusViewNumber = viewNumber;
        this.heartBeatScheduler.stop();
        this.heartbeatChecker.start();
    }

    List<DoViewChange> doViewChangeMessages = new ArrayList<>();

    private void handleDoViewChange(Message<DoViewChange> message) {
        var doViewChange = message.messagePayload();
        if (viewNumber > doViewChange.viewNumber) { //ignore messages from smaller view numbers;
            return;
        }
        logger.info(getName() + " Received DoViewChange from " + message.getFromAddress() + " for view " + doViewChange.viewNumber);
        doViewChangeCounter++;
        doViewChangeMessages.add(doViewChange);
        if (doViewChangeCounter == majorityQuorum()) {
            logger.info("");
            DoViewChange selectedViewChange = pickViewChangeMessageWithHighestNormalViewnumber(doViewChangeMessages);
            this.log = selectedViewChange.log;
            this.opNumber = selectedViewChange.opNumber;
            this.commitNumber = maxCommitNumber(doViewChangeMessages);
            this.status = Status.Normal;
            this.viewNumber = doViewChange.viewNumber;
            this.normalStatusViewNumber = doViewChange.viewNumber;
            heartbeatChecker.stop();
            heartBeatScheduler.start();
            logger.info(getName() + " DoViewChange quorum reached. Starting view " + this.viewNumber);
            sendOnewayMessageToOtherReplicas(new StartView(this.log, this.opNumber, this.commitNumber));
        }
    }

    private int maxCommitNumber(List<DoViewChange> doViewChangeMessages) {
        return doViewChangeMessages.stream().map(d -> d.commitNumber).max(Comparator.comparingInt(c -> c)).orElse(0);
    }

    private DoViewChange pickViewChangeMessageWithHighestNormalViewnumber(List<DoViewChange> doViewChangeMessages) {
        return doViewChangeMessages.stream().max(Comparator.comparingInt((DoViewChange d) -> d.normalStatusViewNumber)
                .thenComparingInt(d -> d.opNumber)).get();
    }

    private void handleStartViewChange(Message<StartViewChange> message) {
        var startViewChange = message.messagePayload();
        if (viewNumber < startViewChange.viewNumber) {
            transitionToViewChange();
        }
        startViewChangeCounter++;
        if (startViewChangeCounter == majorityQuorum()) {
            InetAddressAndPort primaryForView = configuration.getPrimaryForView(viewNumber);
            logger.info(getName() + " StartViewChange quorum reached." + primaryForView + " is the new primary." + " Sending DoViewChange");
            sendOneway(primaryForView, new DoViewChange(viewNumber, log, normalStatusViewNumber, opNumber, commitNumber), message.getCorrelationId());
        }
    }

    private void handleCommit(Message<Commit> message) {
        var commit = message.messagePayload();
        logger.info(getName() + " Handling commit/heartbeat request from " + message.getFromAddress() + " at " + super.clock.now());
        markHeartbeatReceived();
        //TODO: if missing log entries upto commitNumber, initiate state change
        if (commit.viewNumber == this.viewNumber && this.commitNumber < commit.commitNumber) {
            this.commitNumber = commit.commitNumber;
            applyEntryAt(commitNumber);
        }
    }

    private void handlePrepareOk(Message<PrepareOK> message) {
        var prepareOK = message.messagePayload();
        LogEntry logEntry = log.get(prepareOK.opNumber);
        logEntry.prepareOK();
        maybeIncrementCommitNumberAndApply();
        sendOnewayMessageToOtherReplicas(new Commit(viewNumber, commitNumber));
    }

    //0, 1, 2
    void maybeIncrementCommitNumberAndApply() {
        //from last commit number to the entry which is quorum accepted.
        for (int i = commitNumber + 1; i <= log.size(); i++) {
            LogEntry logEntry = log.get(i);
            if (logEntry == null || !logEntry.isQuorumAccepted(majorityQuorum())) {
                break;
            }
            commitNumber = i;
            applyEntryAt(commitNumber);
        }
    }

    Map<String, String> kv = new HashMap<>();
    private void applyEntryAt(int commitNumber) {
        logger.info(getName() + " Handling commit " + commitNumber);
        LogEntry logEntry = log.get(commitNumber);
        if (logEntry == null) {
            return;
        }
        ExecuteCommandRequest request = logEntry.request;
        Command command = Command.deserialize(new ByteArrayInputStream(request.command));
        if (command instanceof SetValueCommand setValueCommand) {
            kv.put(setValueCommand.getKey(), setValueCommand.getValue());
            //complete pending client requests;
            pendingRquests.handleResponse(commitNumber, new ExecuteCommandResponse(Optional.of(setValueCommand.getValue()), true));
        } else if (command instanceof CompareAndSwap casCommand) {
            String existingValue = kv.get(casCommand.getKey());
            if (casCommand.getExistingValue().equals(existingValue)) {
                kv.put(casCommand.getKey(), casCommand.getNewValue());
                pendingRquests.handleResponse(commitNumber, new ExecuteCommandResponse(Optional.of("Success"), true));
                return;
            }
            //
            pendingRquests.handleResponse(commitNumber, new ExecuteCommandResponse(Optional.of("Can not execute CAS because existingValue " + existingValue + " does not match " + casCommand.getExistingValue()), false));
        }
    }


    public static class LogEntry {
        public final ExecuteCommandRequest request;
        public LogEntry(ExecuteCommandRequest request) {
            this.request = request;
        }
        private int acks;
        public void prepareOK() {
            acks++;
        }

        public boolean isQuorumAccepted(int quorum) {
            return acks == quorum;
        }
    }

    public CompletableFuture<ExecuteCommandResponse> handleClientRequest(ExecuteCommandRequest request) {
        if (!isPrimaryForView(viewNumber, getPeerConnectionAddress())) {
            return CompletableFuture.completedFuture(ExecuteCommandResponse.errorResponse("Not processing as the request is sent to backup."));
        };

        opNumber = opNumber + 1;
        log.put(opNumber, new LogEntry(request));

        CompletionCallback<ExecuteCommandResponse> callback = new CompletionCallback();
        pendingRquests.add(opNumber, callback);

        sendOnewayMessageToOtherReplicas(new Prepare(viewNumber, request, opNumber, commitNumber));
        return callback.getFuture();
    }

    //if viewchange is higher than the node.
    // Then
    public void handlePrepare(Message<Prepare> message) {
        Prepare prepare = message.messagePayload();
        if (this.viewNumber == prepare.viewNumber) {
            this.opNumber = this.opNumber + 1;
            this.log.put(opNumber, new LogEntry(prepare.request));
            sendOneway(message.getFromAddress(), new PrepareOK(this.viewNumber, this.opNumber, getReplicaIndex(), true), message.getCorrelationId());
        }
    }

    private int getReplicaIndex() {
        return configuration.replicaIndex(getPeerConnectionAddress());
    }

    private boolean isPrimary() {
        return isPrimaryForView(viewNumber, this.getPeerConnectionAddress());
    }

    private boolean isPrimaryForView(int viewNumber, InetAddressAndPort peerConnectionAddress) {
        return peerConnectionAddress.equals(configuration.getPrimaryForView(viewNumber));
    }

    public void shutdown() {
        logger.info(getName() + " shuting down");
        super.shutdown();
        this.heartbeatChecker.stop();
        this.heartBeatScheduler.stop();
    }
}
