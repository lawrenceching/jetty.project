//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.tests.client;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anything;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.RawFrameBuilder;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSConnection;
import org.eclipse.jetty.websocket.tests.UntrustedWSEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSServer;
import org.eclipse.jetty.websocket.tests.UntrustedWSSession;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class ClientCloseTest
{
    private static final Logger LOG = Log.getLogger(ClientCloseTest.class);
    
    @Rule
    public TestName testname = new TestName();
    
    @Rule
    public TestTracker tt = new TestTracker();
    
    private UntrustedWSServer server;
    private WebSocketClient client;
    
    private void confirmConnection(TrackingEndpoint clientSocket, Future<Session> clientFuture, UntrustedWSSession serverSession) throws Exception
    {
        // Wait for client connect on via future
        Session clientSession = clientFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        clientSession.getRemote().setBatchMode(BatchMode.OFF);
        
        // Wait for client connect via client websocket
        assertThat("Client WebSocket is Open", clientSocket.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS), is(true));
        
        UntrustedWSEndpoint serverEndpoint = serverSession.getUntrustedEndpoint();
        
        // Send message from client to server
        final String echoMsg = "echo-test";
        Future<Void> testFut = clientSocket.getRemote().sendStringByFuture(echoMsg);
        
        // Wait for send future
        testFut.get(30, TimeUnit.SECONDS);
        
        // Read Frame on server side
        WebSocketFrame frame = serverEndpoint.framesQueue.poll(10, TimeUnit.SECONDS);
        assertThat("Server received frame", frame.getOpCode(), is(OpCode.TEXT));
        assertThat("Server received frame payload", frame.getPayloadAsUTF8(), is(echoMsg));
        
        // Server send echo reply
        serverEndpoint.getRemote().sendString(echoMsg);
        
        // Wait for received echo
        String incomingMessage = clientSocket.messageQueue.poll(1, TimeUnit.SECONDS);
        
        // Verify received message
        assertThat("Received message", incomingMessage, is(echoMsg));
        
        // Verify that there are no errors
        assertThat("Error events", clientSocket.error.get(), nullValue());
    }
    
    public static class TestClientTransportOverHTTP extends HttpClientTransportOverHTTP
    {
        @Override
        protected SelectorManager newSelectorManager(HttpClient client)
        {
            return new ClientSelectorManager(client, 1)
            {
                @Override
                protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key)
                {
                    TestEndPoint endPoint = new TestEndPoint(channel, selector, key, getScheduler());
                    endPoint.setIdleTimeout(client.getIdleTimeout());
                    return endPoint;
                }
            };
        }
    }
    
    public static class TestEndPoint extends SocketChannelEndPoint
    {
        public AtomicBoolean congestedFlush = new AtomicBoolean(false);
        
        public TestEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler)
        {
            super((SocketChannel) channel, selector, key, scheduler);
        }
        
        @Override
        public boolean flush(ByteBuffer... buffers) throws IOException
        {
            boolean flushed = super.flush(buffers);
            congestedFlush.set(!flushed);
            return flushed;
        }
    }
    
    @Before
    public void startClient() throws Exception
    {
        HttpClient httpClient = new HttpClient(new TestClientTransportOverHTTP(), null);
        client = new WebSocketClient(httpClient);
        client.addBean(httpClient);
        client.start();
    }
    
    @Before
    public void startServer() throws Exception
    {
        server = new UntrustedWSServer();
        server.start();
    }
    
    @After
    public void stopClient() throws Exception
    {
        client.stop();
    }
    
    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void testHalfClose() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);
        
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        CompletableFuture<UntrustedWSSession> serverSessionFut = new CompletableFuture<>();
        server.registerOnOpenFuture(wsUri, serverSessionFut);
        
        // Client connects
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);
        
        // Server accepts connect
        UntrustedWSSession serverSession = serverSessionFut.get(10, TimeUnit.SECONDS);
        
        // client confirms connection via echo
        confirmConnection(clientSocket, clientConnectFuture, serverSession);
        
        // client sends close frame (code 1000, normal)
        final String origCloseReason = "Normal Close";
        clientSocket.close(StatusCode.NORMAL, origCloseReason);
        
        // server receives close frame
        serverSession.getUntrustedEndpoint().assertCloseInfo("Server", StatusCode.NORMAL, is(origCloseReason));
        
        // server sends 2 messages
        RemoteEndpoint remote = serverSession.getRemote();
        remote.sendString("Hello");
        remote.sendString("World");
        
        // server sends close frame (code 1000, no reason)
        serverSession.close(StatusCode.NORMAL, "From Server");
        
        // client receives 2 messages
        String incomingMessage;
        incomingMessage = clientSocket.messageQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Received message 1", incomingMessage, is("Hello"));
        incomingMessage = clientSocket.messageQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Received message 1", incomingMessage, is("World"));
        
        // Verify that there are no errors
        clientSocket.assertNoErrorEvents("Client");
        
        // client close event on ws-endpoint
        assertTrue("Client close event", clientSocket.closeLatch.await(Defaults.CLOSE_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        clientSocket.assertCloseInfo("Client", StatusCode.NORMAL, containsString("From Server"));
    }
    
    @Test
    public void testNetworkCongestion() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);
        
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        CompletableFuture<UntrustedWSSession> serverSessionFut = new CompletableFuture<>();
        server.registerOnOpenFuture(wsUri, serverSessionFut);
        
        // Client connects
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);
        
        // Server accepts connect
        UntrustedWSSession serverSession = serverSessionFut.get(10, TimeUnit.SECONDS);
        
        // client confirms connection via echo
        confirmConnection(clientSocket, clientConnectFuture, serverSession);
        
        // client sends BIG frames (until it cannot write anymore)
        // server must not read (for test purpose, in order to congest connection)
        // when write is congested, client enqueue close frame
        // client initiate write, but write never completes
        EndPoint endp = clientSocket.getJettyEndPoint();
        assertThat("EndPoint is testable", endp, instanceOf(TestEndPoint.class));
        TestEndPoint testendp = (TestEndPoint) endp;
        
        char msg[] = new char[10240];
        int writeCount = 0;
        long writeSize = 0;
        int i = 0;
        while (!testendp.congestedFlush.get())
        {
            int z = i - ((i / 26) * 26);
            char c = (char) ('a' + z);
            Arrays.fill(msg, c);
            clientSocket.getRemote().sendStringByFuture(String.valueOf(msg));
            writeCount++;
            writeSize += msg.length;
        }
        LOG.info("Wrote {} frames totalling {} bytes of payload before congestion kicked in", writeCount, writeSize);
        
        // Verify timeout error
        clientSocket.assertErrorEvent("Client", instanceOf(SocketTimeoutException.class), anything());
    }
    
    @Test
    public void testProtocolException() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);
        
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        CompletableFuture<UntrustedWSSession> serverSessionFut = new CompletableFuture<>();
        server.registerOnOpenFuture(wsUri, serverSessionFut);
        
        // Client connects
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);
        
        // Server accepts connect
        UntrustedWSSession serverSession = serverSessionFut.get(10, TimeUnit.SECONDS);
        
        // client confirms connection via echo
        confirmConnection(clientSocket, clientConnectFuture, serverSession);
        
        // client should not have received close message (yet)
        clientSocket.assertNotClosed("Client");
        
        // server sends bad close frame (too big of a reason message)
        byte msg[] = new byte[400];
        Arrays.fill(msg, (byte) 'x');
        ByteBuffer bad = ByteBuffer.allocate(500);
        RawFrameBuilder.putOpFin(bad, OpCode.CLOSE, true);
        RawFrameBuilder.putLength(bad, msg.length + 2, false);
        bad.putShort((short) StatusCode.NORMAL);
        bad.put(msg);
        BufferUtil.flipToFlush(bad, 0);
        try (StacklessLogging ignored = new StacklessLogging(Parser.class))
        {
            serverSession.getUntrustedConnection().writeRaw(bad);
            
            // client should have noticed the error
            clientSocket.assertErrorEvent("Client", instanceOf(ProtocolException.class), containsString("Invalid control frame"));
            
            // client parse invalid frame, notifies server of close (protocol error)
            serverSession.getUntrustedEndpoint().assertCloseInfo("Server", StatusCode.PROTOCOL, allOf(containsString("Invalid control frame"), containsString("length")));
        }
        
        // server disconnects
        serverSession.disconnect();
        
        // client close event on ws-endpoint
        assertTrue("Client close event", clientSocket.closeLatch.await(Defaults.CLOSE_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        clientSocket.assertCloseInfo("Client", StatusCode.PROTOCOL, allOf(containsString("Invalid control frame"), containsString("length")));
    }
    
    @Test
    public void testReadEOF() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);
        
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        CompletableFuture<UntrustedWSSession> serverSessionFut = new CompletableFuture<>();
        server.registerOnOpenFuture(wsUri, serverSessionFut);
        
        // Client connects
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);
        
        // Server accepts connect
        UntrustedWSSession serverSession = serverSessionFut.get(10, TimeUnit.SECONDS);
        
        // client confirms connection via echo
        confirmConnection(clientSocket, clientConnectFuture, serverSession);
        
        try (StacklessLogging ignored = new StacklessLogging(clientSocket.LOG))
        {
            // client sends close frame
            final String origCloseReason = "Normal Close";
            clientSocket.close(StatusCode.NORMAL, origCloseReason);
            
            // server receives close frame
            serverSession.getUntrustedEndpoint().assertCloseInfo("Server", StatusCode.NORMAL, is(origCloseReason));
            
            // client should not have received close message (yet)
            clientSocket.assertNotClosed("Client");
            
            // server shuts down connection (no frame reply)
            serverSession.disconnect();
            
            // client reads -1 (EOF)
            clientSocket.assertErrorEvent("Client", instanceOf(IOException.class), containsString("EOF"));
            // client triggers close event on client ws-endpoint
            assertTrue("Client close event", clientSocket.closeLatch.await(Defaults.CLOSE_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            clientSocket.assertCloseInfo("Client", StatusCode.ABNORMAL, containsString("Disconnected"));
        }
    }
    
    @Test
    public void testServerNoCloseHandshake() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);
        
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        CompletableFuture<UntrustedWSSession> serverSessionFut = new CompletableFuture<>();
        server.registerOnOpenFuture(wsUri, serverSessionFut);
        
        // Client connects
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);
        
        // Server accepts connect
        UntrustedWSSession serverSession = serverSessionFut.get(10, TimeUnit.SECONDS);
        UntrustedWSConnection serverConn = serverSession.getUntrustedConnection();
        
        // client confirms connection via echo
        confirmConnection(clientSocket, clientConnectFuture, serverSession);
        
        // client sends close frame
        final String origCloseReason = "Normal Close";
        clientSocket.close(StatusCode.NORMAL, origCloseReason);
        
        // server receives close frame
        serverSession.getUntrustedEndpoint().assertCloseInfo("Server", StatusCode.NORMAL, is(origCloseReason));
        
        // client should not have received close message (yet)
        clientSocket.assertNotClosed("Client");
        
        // server never sends close frame handshake
        // server sits idle
        
        // client idle timeout triggers close event on client ws-endpoint
        clientSocket.assertErrorEvent("Client", instanceOf(SocketTimeoutException.class), containsString("Timeout on Read"));
    }
    
    @Test(timeout = 5000L)
    public void testStopLifecycle() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);
        
        int clientCount = 3;
        
        TrackingEndpoint clientSockets[] = new TrackingEndpoint[clientCount];
        UntrustedWSSession serverSessions[] = new UntrustedWSSession[clientCount];
        
        // Connect Multiple Clients
        for (int i = 0; i < clientCount; i++)
        {
            URI wsUri = server.getUntrustedWsUri(this.getClass(), testname).resolve(Integer.toString(i));
            CompletableFuture<UntrustedWSSession> serverSessionFut = new CompletableFuture<>();
            server.registerOnOpenFuture(wsUri, serverSessionFut);
            
            // Client Request Upgrade
            clientSockets[i] = new TrackingEndpoint(testname.getMethodName() + "[" + i + "]");
            Future<Session> clientConnectFuture = client.connect(clientSockets[i], wsUri);
            
            // Server accepts connection
            serverSessions[i] = serverSessionFut.get(10, TimeUnit.SECONDS);
            
            // client confirms connection via echo
            confirmConnection(clientSockets[i], clientConnectFuture, serverSessions[i]);
        }
        
        // client lifecycle stop
        // every open client should send a close frame
        client.stop();
        
        // clients send close frames (code 1001, shutdown)
        for (int i = 0; i < clientCount; i++)
        {
            // server receives close frame
            UntrustedWSEndpoint serverEndpoint = serverSessions[i].getUntrustedEndpoint();
            assertTrue("Close of server session[" + i + "]", serverEndpoint.closeLatch.await(Defaults.CLOSE_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            serverEndpoint.assertCloseInfo("Server", StatusCode.SHUTDOWN, containsString("Shutdown"));
        }
        
        // clients disconnect
        for (int i = 0; i < clientCount; i++)
        {
            assertTrue("Close of client endpoint[" + i + "]", clientSockets[i].closeLatch.await(1, TimeUnit.SECONDS));
            clientSockets[i].assertCloseInfo("Client", StatusCode.SHUTDOWN, containsString("Shutdown"));
        }
    }
    
    @Test
    public void testWriteException() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);
        
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        CompletableFuture<UntrustedWSSession> serverSessionFut = new CompletableFuture<>();
        server.registerOnOpenFuture(wsUri, serverSessionFut);
        
        // Client connects
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);
        
        // Server accepts connect
        UntrustedWSSession serverSession = serverSessionFut.get(10, TimeUnit.SECONDS);
        
        // client confirms connection via echo
        confirmConnection(clientSocket, clientConnectFuture, serverSession);
        
        // setup client endpoint for write failure (test only)
        EndPoint endp = clientSocket.getJettyEndPoint();
        endp.shutdownOutput();
        
        // client enqueue close frame
        // client write failure
        final String origCloseReason = "Normal Close";
        clientSocket.close(StatusCode.NORMAL, origCloseReason);
        
        assertThat("OnError", clientSocket.error.get(), instanceOf(EofException.class));
        
        // client triggers close event on client ws-endpoint
        // assert - close code==1006 (abnormal)
        // assert - close reason message contains (write failure)
        assertTrue("Client onClose not called", clientSocket.closeLatch.getCount() > 0);
    }
}