/**
 * $RCSfile$
 * $Revision$
 * $Date$
 *
 * Copyright (C) 2002-2003 Jive Software. All rights reserved.
 * ====================================================================
 * The Jive Software License (based on Apache Software License, Version 1.1)
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution,
 *    if any, must include the following acknowledgment:
 *       "This product includes software developed by
 *        Jive Software (http://www.jivesoftware.com)."
 *    Alternately, this acknowledgment may appear in the software itself,
 *    if and wherever such third-party acknowledgments normally appear.
 *
 * 4. The names "Smack" and "Jive Software" must not be used to
 *    endorse or promote products derived from this software without
 *    prior written permission. For written permission, please
 *    contact webmaster@jivesoftware.com.
 *
 * 5. Products derived from this software may not be called "Smack",
 *    nor may "Smack" appear in their name, without prior written
 *    permission of Jive Software.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL JIVE SOFTWARE OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 */
package org.jivesoftware.smackx;

import java.util.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.packet.*;

/**
 * A MultiUserChat is a conversation that takes place among many users in a virtual
 * room. A room could have many occupants with different affiliation and roles.
 * Possible affiliatons are "owner", "admin", "member", and "outcast". Possible roles 
 * are "moderator", "participant", and "visitor". Each role and affiliation guarantees
 * different privileges (e.g. Send messages to all occupants, Kick participants and visitors,
 * Grant voice, Edit member list, etc.). 
 *
 * @author Gaston Dombiak
 */
public class MultiUserChat {

    private XMPPConnection connection;
    private String room;
    private String nickname = null;
    private boolean joined = false;
    private List participants = new ArrayList();

    private PacketFilter presenceFilter;
    private PacketFilter messageFilter;
    private PacketCollector messageCollector;

    public MultiUserChat(XMPPConnection connection, String room) {
        this.connection = connection;
        this.room = room;
        // Create a collector for all incoming messages.
        messageFilter =
            new AndFilter(new FromContainsFilter(room), new PacketTypeFilter(Message.class));
        messageFilter = new AndFilter(messageFilter, new PacketFilter() {
            public boolean accept(Packet packet) {
                Message msg = (Message) packet;
                return msg.getType() == Message.Type.GROUP_CHAT;
            }
        });
        messageCollector = connection.createPacketCollector(messageFilter);
        // Create a listener for all presence updates.
        presenceFilter =
            new AndFilter(new FromContainsFilter(room), new PacketTypeFilter(Presence.class));
        connection.addPacketListener(new PacketListener() {
            public void processPacket(Packet packet) {
                Presence presence = (Presence) packet;
                String from = presence.getFrom();
                if (presence.getType() == Presence.Type.AVAILABLE) {
                    synchronized (participants) {
                        if (!participants.contains(from)) {
                            participants.add(from);
                        }
                    }
                }
                else if (presence.getType() == Presence.Type.UNAVAILABLE) {
                    synchronized (participants) {
                        participants.remove(from);
                    }
                }
            }
        }, presenceFilter);
    }

    /**
     * Returns the name of the room this GroupChat object represents.
     *
     * @return the groupchat room name.
     */
    public String getRoom() {
        return room;
    }

    public void join(String nickname) throws XMPPException {
        join(nickname, SmackConfiguration.getPacketReplyTimeout(), null, -1, -1, -1, null);
    }

    public void join(String nickname, String password) throws XMPPException {
        join(nickname, SmackConfiguration.getPacketReplyTimeout(), password, -1, -1, -1, null);
    }

    // TODO Review protocol (too many params). Use setters or history class?
    public synchronized void join(
        String nickname,
        long timeout,
        String password,
        int maxchars,
        int maxstanzas,
        int seconds,
        Date since)
        throws XMPPException {
        if (nickname == null || nickname.equals("")) {
            throw new IllegalArgumentException("Nickname must not be null or blank.");
        }
        // If we've already joined the room, leave it before joining under a new
        // nickname.
        if (joined) {
            leave();
        }
        // We join a room by sending a presence packet where the "to"
        // field is in the form "roomName@service/nickname"
        Presence joinPresence = new Presence(Presence.Type.AVAILABLE);
        joinPresence.setTo(room + "/" + nickname);

        // Indicate the the client supports MUC          
        MUCInitialPresence mucInitialPresence = new MUCInitialPresence();
        if (password != null) {
            mucInitialPresence.setPassword(password);
        }
        if (maxchars > -1 || maxstanzas > -1 || seconds > -1 || since != null) {
            MUCInitialPresence.History history = new MUCInitialPresence.History();
            if (maxchars > -1) {
                history.setMaxChars(maxchars);
            }
            if (maxstanzas > -1) {
                history.setMaxStanzas(maxstanzas);
            }
            if (seconds > -1) {
                history.setSeconds(seconds);
            }
            if (since != null) {
                history.setSince(since);
            }
            mucInitialPresence.setHistory(history);
        }
        joinPresence.addExtension(mucInitialPresence);

        // Wait for a presence packet back from the server.
        PacketFilter responseFilter =
            new AndFilter(
                new FromContainsFilter(room + "/" + nickname),
                new PacketTypeFilter(Presence.class));
        PacketCollector response = connection.createPacketCollector(responseFilter);
        // Send join packet.
        connection.sendPacket(joinPresence);
        // Wait up to a certain number of seconds for a reply.
        Presence presence = (Presence) response.nextResult(timeout);
        if (presence == null) {
            throw new XMPPException("No response from server.");
        }
        else if (presence.getError() != null) {
            throw new XMPPException(presence.getError());
        }
        this.nickname = nickname;
        joined = true;
    }

    /**
     * Returns true if currently in the group chat (after calling the {@link
     * #join(String)} method.
     *
     * @return true if currently in the group chat room.
     */
    public boolean isJoined() {
        return joined;
    }

    /**
     * Leave the chat room.
     */
    public synchronized void leave() {
        // If not joined already, do nothing.
        if (!joined) {
            return;
        }
        // We leave a room by sending a presence packet where the "to"
        // field is in the form "roomName@service/nickname"
        Presence leavePresence = new Presence(Presence.Type.UNAVAILABLE);
        leavePresence.setTo(room + "/" + nickname);
        connection.sendPacket(leavePresence);
        // Reset participant information.
        participants = new ArrayList();
        nickname = null;
        joined = false;
    }

    /**
     * Returns the room's configuration form that the room's owner can use. The configuration
     * form allows to set the room's language, enable logging, specify room's type, etc..  
     * 
     * @return the Form that contains the fields to complete together with the instrucions.
     * @throws XMPPException if an error occurs asking the configuration form for the room.
     */
    public Form getConfigurationForm() throws XMPPException {
        MUCOwner iq = new MUCOwner();
        iq.setTo(room);
        iq.setType(IQ.Type.GET);

        // Wait for a presence packet back from the server.
        PacketFilter responseFilter =
            new AndFilter(
                new FromContainsFilter(room),
                new PacketTypeFilter(IQ.class));
        PacketCollector response = connection.createPacketCollector(responseFilter);
        // Request the configuration form to the server.
        connection.sendPacket(iq);
        // Wait up to a certain number of seconds for a reply.
        IQ answer = (IQ) response.nextResult(SmackConfiguration.getPacketReplyTimeout());
        // Stop queuing results
        response.cancel();
        
        if (answer == null) {
            throw new XMPPException("No response from server.");
        }
        else if (answer.getError() != null) {
            throw new XMPPException(iq.getError());
        }
        return Form.getFormFrom(answer);
    }
    
    /**
     * Sends the completed configuration form to the server. The room will be configured
     * with the new settings defined in the form.
     * 
     * @param form the form with the new settings.
     * @throws XMPPException if an error occurs setting the new rooms' configuration.
     */
    public void submitConfigurationForm(Form form) throws XMPPException {
        MUCOwner iq = new MUCOwner();
        iq.setTo(room);
        iq.setType(IQ.Type.SET);
        iq.addExtension(form.getDataForm());

        // Send the completed configuration form to the server.
        connection.sendPacket(iq);
        
        // TODO Check for possible returned errors? permission errors?
        // TODO Check that the form is of type "submit"
    }

    /**
     * Returns the nickname that was used to join the room, or <tt>null</tt> if not
     * currently joined.
     *
     * @return the nickname currently being used.
     */
    public String getNickname() {
        return nickname;
    }

    /**
     * Returns the number of participants in the group chat.<p>
     *
     * Note: this value will only be accurate after joining the group chat, and
     * may fluctuate over time. If you query this value directly after joining the
     * group chat it may not be accurate, as it takes a certain amount of time for
     * the server to send all presence packets to this client.
     *
     * @return the number of participants in the group chat.
     */
    public int getParticipantCount() {
        synchronized (participants) {
            return participants.size();
        }
    }

    /**
     * Returns an Iterator (of Strings) for the list of fully qualified participants
     * in the group chat. For example, "conference@chat.jivesoftware.com/SomeUser".
     * Typically, a client would only display the nickname of the participant. To
     * get the nickname from the fully qualified name, use the
     * {@link org.jivesoftware.smack.util.StringUtils#parseResource(String)} method.
     * Note: this value will only be accurate after joining the group chat, and may
     * fluctuate over time.
     *
     * @return an Iterator for the participants in the group chat.
     */
    public Iterator getParticipants() {
        synchronized (participants) {
            return Collections.unmodifiableList(new ArrayList(participants)).iterator();
        }
    }

    /**
     * Adds a packet listener that will be notified of any new Presence packets
     * sent to the group chat. Using a listener is a suitable way to know when the list
     * of participants should be re-loaded due to any changes.
     *
     * @param listener a packet listener that will be notified of any presence packets
     *      sent to the group chat.
     */
    public void addParticipantListener(PacketListener listener) {
        connection.addPacketListener(listener, presenceFilter);
    }

    /**
     * Sends a message to the chat room.
     *
     * @param text the text of the message to send.
     * @throws XMPPException if sending the message fails.
     */
    public void sendMessage(String text) throws XMPPException {
        Message message = new Message(room, Message.Type.GROUP_CHAT);
        message.setBody(text);
        connection.sendPacket(message);
    }

    /**
     * Creates a new Message to send to the chat room.
     *
     * @return a new Message addressed to the chat room.
     */
    public Message createMessage() {
        return new Message(room, Message.Type.GROUP_CHAT);
    }

    /**
     * Sends a Message to the chat room.
     *
     * @param message the message.
     * @throws XMPPException if sending the message fails.
     */
    public void sendMessage(Message message) throws XMPPException {
        connection.sendPacket(message);
    }

    /**
    * Polls for and returns the next message, or <tt>null</tt> if there isn't
    * a message immediately available. This method provides significantly different
    * functionalty than the {@link #nextMessage()} method since it's non-blocking.
    * In other words, the method call will always return immediately, whereas the
    * nextMessage method will return only when a message is available (or after
    * a specific timeout).
    *
    * @return the next message if one is immediately available and
    *      <tt>null</tt> otherwise.
    */
    public Message pollMessage() {
        return (Message) messageCollector.pollResult();
    }

    /**
     * Returns the next available message in the chat. The method call will block
     * (not return) until a message is available.
     *
     * @return the next message.
     */
    public Message nextMessage() {
        return (Message) messageCollector.nextResult();
    }

    /**
     * Returns the next available message in the chat. The method call will block
     * (not return) until a packet is available or the <tt>timeout</tt> has elapased.
     * If the timeout elapses without a result, <tt>null</tt> will be returned.
     *
     * @param timeout the maximum amount of time to wait for the next message.
     * @return the next message, or <tt>null</tt> if the timeout elapses without a
     *      message becoming available.
     */
    public Message nextMessage(long timeout) {
        return (Message) messageCollector.nextResult(timeout);
    }

    /**
     * Adds a packet listener that will be notified of any new messages in the
     * group chat. Only "group chat" messages addressed to this group chat will
     * be delivered to the listener. If you wish to listen for other packets
     * that may be associated with this group chat, you should register a
     * PacketListener directly with the XMPPConnection with the appropriate
     * PacketListener.
     *
     * @param listener a packet listener.
     */
    public void addMessageListener(PacketListener listener) {
        connection.addPacketListener(listener, messageFilter);
    }
}