package com.strongfellow.ticker;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import javax.annotation.PostConstruct;

import org.bitcoinj.core.Block;
import org.bitcoinj.core.GetDataMessage;
import org.bitcoinj.core.Message;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerEventListener;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.params.MainNetParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import wf.bitcoin.javabitcoindrpcclient.BitcoinJSONRPCClient;
import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.RawTransaction;
import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.RawTransaction.Out;

@Controller
@RequestMapping("/transactions")
public class TransactionController {

    private BitcoinJSONRPCClient client;

    private BlockingQueue<Transaction> transactionQueue =
            new LinkedBlockingQueue<Transaction>();

    private final Executor executor = Executors.newFixedThreadPool(10);
    private static final Logger logger = LoggerFactory.getLogger(TransactionController.class);

    @Autowired private SimpMessagingTemplate template;

    @PostConstruct
    private void init() throws UnknownHostException, InterruptedException, ExecutionException, MalformedURLException {
       this.client = new BitcoinJSONRPCClient(
                "http://" + System.getenv("user") + ":" + System.getenv("password") + "@localhost:8332");

        new Thread() {
            @Override
            public void run() {
                while (true) {
                    try {
                        logger.info("transaction size: " + transactionQueue.size());
                        Transaction t = transactionQueue.take();
                        double value = 0;
                        for (TransactionInput txin : t.getInputs()) {
                            TransactionOutPoint outPoint = txin.getOutpoint();
                            String hash = outPoint.getHash().toString();
                            int index = (int)outPoint.getIndex();
                            RawTransaction rt = client.getRawTransaction(hash);
                            List<Out> outs = rt.vOut();
                            Out out = outs.get(index);
                            value += out.value();
                        }
                        String message = t.getHashAsString() + " -> " + value;
                        logger.info(message);
                        template.convertAndSend("/topic/transactions", message);
                    } catch(Throwable t) {
                        logger.error("throwable", t);
                    }
                }
            }

        } .start();

        logger.info("begin init");
        NetworkParameters params = MainNetParams.get();
        PeerGroup peerGroup = new PeerGroup(params);
        peerGroup.setUseLocalhostPeerWhenPossible(true);

        peerGroup.addEventListener(new PeerEventListener() {

            @Override
            public void onTransaction(Peer peer, Transaction t) {
                transactionQueue.add(t);

                logger.info("baine: {}", t.getHashAsString());
  //              template.convertAndSend("/topic/transactions", t.getHashAsString());
            }

            @Override
            public Message onPreMessageReceived(Peer peer, Message m) {
                return null;
            }

            @Override
            public void onPeerDisconnected(Peer peer, int peerCount) {
            }

            @Override
            public void onPeerConnected(Peer peer, int peerCount) {
            }

            @Override
            public void onChainDownloadStarted(Peer peer, int blocksLeft) {
            }

            @Override
            public void onBlocksDownloaded(Peer peer, Block block, int blocksLeft) {
            }

            @Override
            public List<Message> getData(Peer peer, GetDataMessage m) {
                return null;
            }
        });

        peerGroup.start();
        peerGroup.addAddress(new PeerAddress(InetAddress.getLocalHost(), params.getPort()));
        peerGroup.waitForPeers(1).get();

        logger.info("end init");
    }

    /**
     * Serve the main page
     */
    @RequestMapping(value = "/index.html", method = RequestMethod.GET)
    public String home() {
      return "transactions";
    }

}
