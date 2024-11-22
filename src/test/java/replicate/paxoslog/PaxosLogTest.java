package replicate.paxoslog;

import org.junit.Before;
import org.junit.Test;
import replicate.common.ClusterTest;
import replicate.common.NetworkClient;
import replicate.common.TestUtils;
import replicate.paxos.PaxosState;
import replicate.paxos.messages.GetValueResponse;
import replicate.quorum.messages.GetValueRequest;
import replicate.twophaseexecution.CompareAndSwap;
import replicate.twophaseexecution.messages.ExecuteCommandRequest;
import replicate.twophaseexecution.messages.ExecuteCommandResponse;
import replicate.wal.SetValueCommand;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.Assert.*;

public class PaxosLogTest extends ClusterTest<PaxosLog> {
    @Before
    public void setUp() throws IOException {
        super.nodes = TestUtils.startCluster(Arrays.asList("athens", "byzantium", "cyrene"),
                (name, config, clock, clientConnectionAddress, peerConnectionAddress, peers) -> new PaxosLog(name, clock, config, clientConnectionAddress, peerConnectionAddress, peers));

    }

    @Test
    public void singleValuePaxosTest() throws IOException {
        var networkClient = new NetworkClient();
        byte[] command = new SetValueCommand("title", "Microservices").serialize();
        var setValueResponse = networkClient.sendAndReceive(new ExecuteCommandRequest(command), nodes.get("athens").getClientConnectionAddress(), ExecuteCommandResponse.class).getResult();
        assertEquals(Optional.of("Microservices"), setValueResponse.getResponse());
    }

    @Test
    public void singleValueNullPaxosGetTest() throws IOException {
        var networkClient = new NetworkClient();
        var getValueResponse = networkClient.sendAndReceive(new GetValueRequest("title"), nodes.get("athens").getClientConnectionAddress(), GetValueResponse.class).getResult();
        assertEquals(Optional.empty(), getValueResponse.value);
    }

    @Test
    public void singleValuePaxosGetTest() throws IOException {
        var networkClient = new NetworkClient();
        byte[] command = new SetValueCommand("title", "Microservices").serialize();
        var setValueResponse = networkClient.sendAndReceive(new ExecuteCommandRequest(command), nodes.get("athens").getClientConnectionAddress(), ExecuteCommandResponse.class).getResult();
        assertEquals(Optional.of("Microservices"), setValueResponse.getResponse());
        var getValueResponse = networkClient.sendAndReceive(new GetValueRequest("title"), nodes.get("athens").getClientConnectionAddress(), GetValueResponse.class).getResult();
        assertEquals(Optional.of("Microservices"), getValueResponse.value);
    }

    @Test
    public void executeMultipleCommands() throws IOException {
        var networkClient = new NetworkClient();
        PaxosLog athens = nodes.get("athens");
        PaxosLog byzantium = nodes.get("byzantium");
        PaxosLog cyrene = nodes.get("cyrene");

        var command = new SetValueCommand("title", "Microservices");
        var setValueResponse = networkClient.sendAndReceive(new ExecuteCommandRequest(command.serialize()), athens.getClientConnectionAddress(), ExecuteCommandResponse.class).getResult();
        assertEquals(Optional.of("Microservices"), setValueResponse.getResponse());

        command = new SetValueCommand("title2", "Distributed Systems");
        setValueResponse = networkClient.sendAndReceive(new ExecuteCommandRequest(command.serialize()), byzantium.getClientConnectionAddress(), ExecuteCommandResponse.class).getResult();
        assertEquals(Optional.of("Distributed Systems"), setValueResponse.getResponse());

        assertEquals(2, athens.paxosLog.size());
        assertEquals(2, byzantium.paxosLog.size());
        assertEquals(2, cyrene.paxosLog.size());

        CompareAndSwap casCommand = new CompareAndSwap("title", Optional.empty(), "Microservices");
        var casResponse
                = networkClient.sendAndReceive(new ExecuteCommandRequest(casCommand.serialize()), athens.getClientConnectionAddress(), ExecuteCommandResponse.class).getResult();
        assertEquals(false, casResponse.isCommitted());
        assertEquals(Optional.of("Microservices"), casResponse.getResponse());

        assertEquals(3, athens.paxosLog.size());
        assertEquals(3, byzantium.paxosLog.size());
        assertEquals(3, cyrene.paxosLog.size());


        casCommand = new CompareAndSwap("title", Optional.of("Microservices"), "Event Driven Microservices");
        casResponse
                = networkClient.sendAndReceive(new ExecuteCommandRequest(casCommand.serialize()), byzantium.getClientConnectionAddress(), ExecuteCommandResponse.class).getResult();
        assertEquals(true, casResponse.isCommitted());
        assertEquals(Optional.of("Microservices"), casResponse.getResponse());

        assertEquals(4, athens.paxosLog.size());
        assertEquals(4, byzantium.paxosLog.size());
        assertEquals(4, cyrene.paxosLog.size());

        casCommand = new CompareAndSwap("title", Optional.of("Event Driven " +
                "Microservices"), "Testing Paxos ");
        casResponse
                = networkClient.sendAndReceive(new ExecuteCommandRequest(casCommand.serialize()), byzantium.getClientConnectionAddress(), ExecuteCommandResponse.class).getResult();

        assertEquals(true, casResponse.isCommitted());
        assertEquals(Optional.of("Event Driven Microservices"), casResponse.getResponse());

        assertEquals(5, athens.paxosLog.size());
        assertEquals(5, byzantium.paxosLog.size());
        assertEquals(5, cyrene.paxosLog.size());

        var getValueResponse =
                networkClient.sendAndReceive(new GetValueRequest("title"),
                        athens.getClientConnectionAddress(),
                        GetValueResponse.class).getResult();
        assertEquals(Optional.of("Testing Paxos "), getValueResponse.value);
    }


    @Test
    public void selectsNextIndexIfOtherValueIsSelectedForIndexInPraxosPrepare() throws IOException {
        var networkClient = new NetworkClient();
        PaxosLog athens = nodes.get("athens");
        PaxosLog byzantium = nodes.get("byzantium");
        PaxosLog cyrene = nodes.get("cyrene");

        athens.dropMessagesTo(byzantium);
        athens.dropMessagesTo(cyrene);
        var command = new SetValueCommand("title", "Initial Title");

        //athens has incomplete run for "title", "Microservices"
        var setValueResponse = networkClient.sendAndReceive(new ExecuteCommandRequest(command.serialize()), athens.getClientConnectionAddress(), ExecuteCommandResponse.class);
        assertTrue("Expect an exception, as quorum communication fails after multiple attempts", setValueResponse.isError());
        assertEquals(1, athens.paxosLog.size());
        assertEquals(0, byzantium.paxosLog.size());
        assertEquals(0, cyrene.paxosLog.size());


        //byzantium -> cyrene prepare success
        //byzantium -> cyrene propose success
        //byzantium -> cyrene commit fails. So cyrene does not know if the
        // accepted value at index 1 is committed or not.
        //byzantium and cyrene both have title, distributed systems at index 1
        //byzantium could successfully commit it but cyrene does not know
        // about it.
        byzantium.dropAfterNMessagesTo(cyrene, 2);

        command = new SetValueCommand("title", "Updated Title");
        var response1 = networkClient.sendAndReceive(new ExecuteCommandRequest(command.serialize()), byzantium.getClientConnectionAddress(), ExecuteCommandResponse.class);
        assertTrue("Expect an exception, as quorum communication fails after multiple attempts", response1.isError());
        assertEquals(1, athens.paxosLog.size());
        assertEquals(1, byzantium.paxosLog.size());
        assertEquals(1, cyrene.paxosLog.size());

        athens.reconnectTo(cyrene);
        byzantium.reconnectTo(cyrene);

        //this should go on index 2. Index 1 needs to be reconciled with
        // Distributed Systems chosen over Microservices
        var casCommand = new CompareAndSwap("title",
                    Optional.of("Updated Title"), "CAS Title");
        var casResponse = networkClient.sendAndReceive(new ExecuteCommandRequest(casCommand.serialize()), cyrene.getClientConnectionAddress(), ExecuteCommandResponse.class).getResult();

        //cas returns existing value, which is modified by CAS.
        assertEquals(Optional.of("Updated Title"),
                casResponse.getResponse());

        assertEquals(2, athens.paxosLog.size());
        assertEquals(2, byzantium.paxosLog.size());
        assertEquals(2, cyrene.paxosLog.size());

        //So even if athens had different entry at index 0, it was reconciled
        //and all the nodes have same entries in same order. The CAS command
        //succeeds on all the nodes to have final value as CAS Title.
        assertEquals("CAS Title", athens.getValue(
                "title"));
        assertEquals("CAS Title", byzantium.getValue("title"));
        assertEquals("CAS Title", cyrene.getValue("title"));

    }


    @Test
    public void triesNextLogIndexOnlyAfterCommittingValueAtCurrentIndex() throws IOException, InterruptedException {
        var networkClient = new NetworkClient();
        PaxosLog athens = nodes.get("athens");
        PaxosLog byzantium = nodes.get("byzantium");
        PaxosLog cyrene = nodes.get("cyrene");

        athens.dropAfterNMessagesTo(byzantium, 1); //prepare succeeds, propose fails. So propose succeeds only on athens.
        athens.dropMessagesTo(cyrene);
        {
            var command = new SetValueCommand("title", "Microservices");
            var setValueResponse = networkClient.sendAndReceive(new ExecuteCommandRequest(command.serialize()), athens.getClientConnectionAddress(), ExecuteCommandResponse.class);
            assertTrue("Except an exception, as quorum communication fails after multiple attempts", setValueResponse.isError());
        }
        assertEquals(1, athens.paxosLog.size());
        assertEquals(1, byzantium.paxosLog.size());
        assertEquals(0, cyrene.paxosLog.size());

        athens.reconnectTo(cyrene);

        var command = new SetValueCommand("newTitle", "Event Driven Microservices");
        ExecuteCommandRequest request = new ExecuteCommandRequest(command.serialize());
        var setValueResponse = networkClient.sendAndReceive(request, cyrene.getClientConnectionAddress(), ExecuteCommandResponse.class).getResult();
        assertEquals(Optional.of("Event Driven Microservices"), setValueResponse.getResponse());

        assertEquals(2, byzantium.paxosLog.size());
        assertEquals(2, cyrene.paxosLog.size());
        assertEquals(2, athens.paxosLog.size());

    }
}