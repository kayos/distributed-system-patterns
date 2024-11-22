package replicate.generationvoting;

import org.junit.Test;
import replicate.common.ClusterTest;
import replicate.common.NetworkClient;
import replicate.common.TestUtils;
import replicate.generationvoting.messages.NextNumberRequest;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class GenerationVotingTest extends ClusterTest<GenerationVoting> {

    @Test
    public void generateMonotonicNumbersWithQuorumVoting() throws IOException {
        super.nodes = TestUtils.startCluster( Arrays.asList("athens", "byzantium", "cyrene"), (name, config, clock, clientConnectionAddress, peerConnectionAddress, peerAddresses)
                -> new GenerationVoting(name, config, clock, clientConnectionAddress, peerConnectionAddress, peerAddresses));
        GenerationVoting athens = nodes.get("athens");
        GenerationVoting byzantium = nodes.get( "byzantium");
        GenerationVoting cyrene = nodes.get("cyrene");

        NetworkClient client = new NetworkClient();
        Integer nextNumber = client.sendAndReceive(new NextNumberRequest(), athens.getClientConnectionAddress(), Integer.class).getResult();
        assertEquals(1, nextNumber.intValue());
        assertEquals(1, athens.generation);
        assertEquals(1, byzantium.generation);
        assertEquals(1, cyrene.generation);

        nextNumber = client.sendAndReceive(new NextNumberRequest(), athens.getClientConnectionAddress(), Integer.class).getResult();

        assertEquals(2, nextNumber.intValue());
        assertEquals(2, athens.generation);
        assertEquals(2, byzantium.generation);
        assertEquals(2, cyrene.generation);

        nextNumber = client.sendAndReceive(new NextNumberRequest(), athens.getClientConnectionAddress(), Integer.class).getResult();

        assertEquals(3, nextNumber.intValue());
        assertEquals(3, athens.generation);
        assertEquals(3, byzantium.generation);
        assertEquals(3, cyrene.generation);
    }

    @Test //FIXME. Fails for numbers 6 and above.
    public void getsMonotonicNumbersWithFailures() throws IOException {
        super.nodes = TestUtils.startCluster( Arrays.asList("athens", "byzantium", "cyrene", "delphi", "ephesus"), (name, config, clock, clientConnectionAddress, peerConnectionAddress, peerAddresses) -> new GenerationVoting(name, config, clock, clientConnectionAddress, peerConnectionAddress, peerAddresses));
        GenerationVoting athens = nodes.get("athens");
        GenerationVoting byzantium = nodes.get( "byzantium");
        GenerationVoting cyrene = nodes.get("cyrene");
        GenerationVoting delphi = nodes.get("delphi");
        GenerationVoting ephesus = nodes.get("ephesus");

        athens.dropMessagesTo(byzantium);
        athens.dropMessagesTo(ephesus);

        NetworkClient client = new NetworkClient();
        Integer firstNumber = client.sendAndReceive(new NextNumberRequest(), athens.getClientConnectionAddress(), Integer.class).getResult();

        assertEquals(1, firstNumber.intValue());
        assertEquals(1, athens.generation);
        assertEquals(0, byzantium.generation);
        assertEquals(1, cyrene.generation);
        assertEquals(1, delphi.generation);
        assertEquals(0, ephesus.generation);


        ephesus.dropMessagesTo(athens);
        ephesus.dropMessagesTo(cyrene);

        Integer secondNumber = client.sendAndReceive(new NextNumberRequest(), ephesus.getClientConnectionAddress(), Integer.class).getResult();


        assertEquals(2, secondNumber.intValue());
        assertEquals(1, athens.generation);
        assertEquals(2, byzantium.generation);
        assertEquals(1, cyrene.generation);
        assertEquals(2, delphi.generation);
        assertEquals(2, ephesus.generation);


        Integer thirdNumber = client.sendAndReceive(new NextNumberRequest(), athens.getClientConnectionAddress(), Integer.class).getResult();
        assertEquals(3, thirdNumber.intValue());
        //try generating more numbers connecting to different nodes.

        Integer fourthNumber = client.sendAndReceive(new NextNumberRequest(), ephesus.getClientConnectionAddress(), Integer.class).getResult();
        assertEquals(4, fourthNumber.intValue());

        Integer fifthNumber = client.sendAndReceive(new NextNumberRequest(), athens.getClientConnectionAddress(), Integer.class).getResult();
        assertEquals(5, fifthNumber.intValue());


        assertEquals(6, nextNumberConnectingTo(athens, client));
        assertEquals(7, nextNumberConnectingTo(byzantium, client));
        assertEquals(8, nextNumberConnectingTo(cyrene, client));
        assertEquals(9, nextNumberConnectingTo(ephesus, client));
        assertEquals(10, nextNumberConnectingTo(delphi, client));
        assertEquals(11, nextNumberConnectingTo(ephesus, client));
        assertEquals(12, nextNumberConnectingTo(byzantium, client));
    }

    private static int nextNumberConnectingTo(GenerationVoting byzantium, NetworkClient client) throws IOException {
        return client.sendAndReceive(new NextNumberRequest(), byzantium.getClientConnectionAddress(), Integer.class).getResult().intValue();
    }

}
