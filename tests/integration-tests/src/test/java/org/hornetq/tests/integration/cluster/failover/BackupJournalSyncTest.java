package org.hornetq.tests.integration.cluster.failover;

import java.util.HashSet;
import java.util.Set;

import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.Interceptor;
import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientProducer;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.core.client.impl.ClientSessionFactoryInternal;
import org.hornetq.core.client.impl.ServerLocatorInternal;
import org.hornetq.core.journal.impl.JournalFile;
import org.hornetq.core.journal.impl.JournalImpl;
import org.hornetq.core.persistence.impl.journal.JournalStorageManager;
import org.hornetq.core.protocol.core.Channel;
import org.hornetq.core.protocol.core.ChannelHandler;
import org.hornetq.core.protocol.core.Packet;
import org.hornetq.core.protocol.core.impl.PacketImpl;
import org.hornetq.core.protocol.core.impl.wireformat.ReplicationJournalFileMessage;
import org.hornetq.core.replication.ReplicationEndpoint;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.spi.core.protocol.RemotingConnection;
import org.hornetq.tests.integration.cluster.util.TestableServer;
import org.hornetq.tests.util.TransportConfigurationUtils;

public class BackupJournalSyncTest extends FailoverTestBase
{

   private ServerLocatorInternal locator;
   private ClientSessionFactoryInternal sessionFactory;
   private ClientSession session;
   private ClientProducer producer;
   private ReplicationChannelHandler handler;
   private static final int N_MSGS = 100;

   @Override
   protected void setUp() throws Exception
   {
      startBackupServer = false;
      super.setUp();
      locator = getServerLocator();
      locator.setBlockOnNonDurableSend(true);
      locator.setBlockOnDurableSend(true);
      locator.setReconnectAttempts(-1);
      sessionFactory = createSessionFactoryAndWaitForTopology(locator, 1);
   }

   public void testNodeID() throws Exception
   {
      backupServer.start();
      waitForComponent(backupServer, 5);
      assertTrue("must be running", backupServer.isStarted());
      assertEquals("backup and live should have the same nodeID", liveServer.getServer().getNodeID(),
                   backupServer.getServer().getNodeID());
   }

   public void testReserveFileIdValuesOnBackup() throws Exception
   {
      handler = new ReplicationChannelHandler();
      liveServer.addInterceptor(new BackupSyncDelay(handler));
      createProducerSendSomeMessages();
      JournalImpl messageJournal = getMessageJournalFromServer(liveServer);
      for (int i = 0; i < 5; i++)
      {
         messageJournal.forceMoveNextFile();
         sendMessages(session, producer, N_MSGS);
      }
      backupServer.start();
      waitForBackup(sessionFactory, 10, false);

      // SEND more messages, now with the backup replicating
      sendMessages(session, producer, N_MSGS);

      handler.notifyAll();
      waitForBackup(sessionFactory, 10, true);

      Set<Long> liveIds = getFileIds(messageJournal);
      assertFalse("should not be initialized", backupServer.getServer().isInitialised());
      crash(session);
      waitForServerInitialization(backupServer.getServer(), 5);

      JournalImpl backupMsgJournal = getMessageJournalFromServer(backupServer);
      Set<Long> backupIds = getFileIds(backupMsgJournal);
      assertEquals("File IDs must match!", liveIds, backupIds);
   }

   private static void waitForServerInitialization(HornetQServer server, int seconds)
   {
      long time = System.currentTimeMillis();
      long toWait = seconds * 1000;
      while (!server.isInitialised())
      {
         try
         {
            Thread.sleep(50);
         }
         catch (InterruptedException e)
         {
            // ignore
         }
         if (System.currentTimeMillis() > (time + toWait))
         {
            fail("component did not start within timeout of " + seconds);
         }
      }
   }

   private Set<Long> getFileIds(JournalImpl journal)
   {
      Set<Long> results = new HashSet<Long>();
      for (JournalFile jf : journal.getDataFiles())
      {
         results.add(Long.valueOf(jf.getFileID()));
      }
      return results;
   }

   static JournalImpl getMessageJournalFromServer(TestableServer server)
   {
      JournalStorageManager sm = (JournalStorageManager)server.getServer().getStorageManager();
      return (JournalImpl)sm.getMessageJournal();
   }

   public void testMessageSync() throws Exception
   {
      createProducerSendSomeMessages();

      receiveMsgs(0, N_MSGS / 2);
      assertFalse("backup is not started!", backupServer.isStarted());

      // BLOCK ON journals
      backupServer.start();

      waitForBackup(sessionFactory, 5);
      crash(session);

      // consume N/2 from 'new' live (the old backup)
      receiveMsgs(N_MSGS / 2, N_MSGS);
   }

   private void createProducerSendSomeMessages() throws HornetQException, Exception
   {
      session = sessionFactory.createSession(true, true);
      session.createQueue(FailoverTestBase.ADDRESS, FailoverTestBase.ADDRESS, null, true);
      producer = session.createProducer(FailoverTestBase.ADDRESS);

      sendMessages(session, producer, N_MSGS);
      session.start();
   }

   private void receiveMsgs(int start, int end) throws HornetQException
   {
      ClientConsumer consumer = session.createConsumer(FailoverTestBase.ADDRESS);
      receiveMessagesAndAck(consumer, start, end);
      session.commit();
   }

   @Override
   protected void tearDown() throws Exception
   {
      if (handler != null)
      {
         handler.notifyAll();
      }
      if (sessionFactory != null)
         sessionFactory.close();
      if (session != null)
         session.close();
      closeServerLocator(locator);

      super.tearDown();
   }

   @Override
   protected void createConfigs() throws Exception
   {
      createReplicatedConfigs();
   }

   @Override
   protected TransportConfiguration getAcceptorTransportConfiguration(boolean live)
   {
      return TransportConfigurationUtils.getInVMAcceptor(live);
   }

   @Override
   protected TransportConfiguration getConnectorTransportConfiguration(boolean live)
   {
      return TransportConfigurationUtils.getInVMConnector(live);
   }

   private class BackupSyncDelay implements Interceptor
   {

      private final ReplicationChannelHandler handler;

      public BackupSyncDelay(ReplicationChannelHandler handler)
      {
         this.handler = handler;
         // TODO Auto-generated constructor stub
      }

      @Override
      public boolean intercept(Packet packet, RemotingConnection connection) throws HornetQException
      {
         if (packet.getType() == PacketImpl.HA_BACKUP_REGISTRATION)
         {
            try
            {
               ReplicationEndpoint repEnd = backupServer.getServer().getReplicationEndpoint();
               handler.addSubHandler(repEnd);
               Channel repChannel = repEnd.getChannel();
               repChannel.setHandler(handler);
            }
            catch (Exception e)
            {
               throw new RuntimeException(e);
            }
         }
         return true;
      }

   }

   private static class ReplicationChannelHandler implements ChannelHandler
   {

      private ChannelHandler handler;

      public void addSubHandler(ChannelHandler handler)
      {
         this.handler = handler;
      }

      @Override
      public void handlePacket(Packet packet)
      {
         System.out.println(packet);
         if (packet.getType() == PacketImpl.REPLICATION_SYNC)
         {
            ReplicationJournalFileMessage syncMsg = (ReplicationJournalFileMessage)packet;
            if (syncMsg.isUpToDate())
            {
               // Hold the message that notifies the backup that sync is done.
               try
               {
                  wait();
               }
               catch (InterruptedException e)
               {
                  // no-op
               }
            }
         }
         handler.handlePacket(packet);
      }

   }
}
