/*
 * Project: mule-extras-pgp
 * Author : ariva
 * Created on 21-mar-2005
 *
 */
package org.mule.extras.pgp.filters;

import cryptix.message.LiteralMessage;
import cryptix.message.Message;
import cryptix.message.MessageFactory;
import cryptix.message.SignedMessage;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mule.MuleManager;
import org.mule.config.i18n.Messages;
import org.mule.extras.pgp.PGPAuthentication;
import org.mule.impl.MuleMessage;
import org.mule.impl.RequestContext;
import org.mule.impl.security.AbstractEndpointSecurityFilter;
import org.mule.umo.UMOEncryptionStrategy;
import org.mule.umo.UMOEvent;
import org.mule.umo.UMOMessage;
import org.mule.umo.lifecycle.InitialisationException;
import org.mule.umo.security.UMOAuthentication;
import org.mule.umo.security.UMOSecurityContext;
import org.mule.umo.security.UnauthorisedException;
import org.mule.umo.security.UnknownAuthenticationTypeException;

import java.io.ByteArrayInputStream;
import java.util.Collection;

/**
 * @author ariva
 *  
 */
public class PGPSecurityFilter extends AbstractEndpointSecurityFilter {

    private UMOEncryptionStrategy strategy;

    private String strategyName;

    protected static transient Log logger = LogFactory.getLog(PGPSecurityFilter.class);

    /*
     * (non-Javadoc)
     * 
     * @see org.mule.impl.security.AbstractEndpointSecurityFilter#authenticateInbound(org.mule.umo.UMOEvent)
     */
    protected void authenticateInbound(UMOEvent event) throws SecurityException, UnauthorisedException, UnknownAuthenticationTypeException
    {

        UMOMessage message = event.getMessage();

        //todo String userId = (String) message.getProperty(MailMessageAdapter.PROPERTY_FROM_ADDRESS);
        String userId = (String) message.getProperty("fromAddress");

        byte[] creds = null;

        try {
            creds = message.getPayloadAsBytes();
            creds = strategy.decrypt(creds, null);
        } catch (Exception e1) {
            throw new UnauthorisedException(new org.mule.config.i18n.Message(Messages.FAILED_TO_READ_PAYLOAD), event.getMessage(), e1);
        }



        UMOAuthentication authResult;
        UMOAuthentication umoAuthentication;

        try {
            umoAuthentication = new PGPAuthentication(userId, decodeMsgRaw(creds));
        } catch (Exception e1) {
            throw new UnauthorisedException(new org.mule.config.i18n.Message(Messages.FAILED_TO_READ_PAYLOAD), event.getMessage(), e1);
        }

        try {
            authResult = getSecurityManager().authenticate(umoAuthentication);
        } catch (Exception e) {
            // Authentication failed
            if (logger.isDebugEnabled()) {
                logger.debug("Authentication request for user: " + userId + " failed: " + e.toString());
            }
            throw new UnauthorisedException(new org.mule.config.i18n.Message(Messages.AUTH_FAILED_FOR_USER_X, userId), event.getMessage(), e);
        }

        // Authentication success
        if (logger.isDebugEnabled()) {
            logger.debug("Authentication success: " + authResult.toString());
        }

        UMOSecurityContext context = getSecurityManager().createSecurityContext(authResult);
        event.getSession().setSecurityContext(context);

        try {
            RequestContext.rewriteEvent(new MuleMessage(getUnencryptedMessageWithoutSignature((PGPAuthentication) authResult),
                    null));
        } catch (Exception e2) {
            throw new UnauthorisedException(event.getMessage(), context, event.getEndpoint(), this);
        }
    }

    private Message decodeMsgRaw(byte[] raw) throws Exception {
        MessageFactory mf = MessageFactory.getInstance("OpenPGP");

        ByteArrayInputStream in = new ByteArrayInputStream(raw);

        Collection msgs = mf.generateMessages(in);

        return (Message) msgs.iterator().next();
    }

    private String getUnencryptedMessageWithoutSignature(PGPAuthentication auth) throws Exception {
        Message msg = (Message) auth.getCredentials();
        if (msg instanceof SignedMessage) {
            msg = ((SignedMessage) msg).getContents();
        }
        if (msg instanceof LiteralMessage) {
            return ((LiteralMessage) msg).getTextData();
        } else {
            throw new Exception("Wrong data");
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mule.impl.security.AbstractEndpointSecurityFilter#authenticateOutbound(org.mule.umo.UMOEvent)
     */
    protected void authenticateOutbound(UMOEvent event) throws SecurityException {
        // TODO
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mule.impl.security.AbstractEndpointSecurityFilter#doInitialise()
     */
    protected void doInitialise() throws InitialisationException {
        if (strategyName != null) {
            strategy = MuleManager.getInstance().getSecurityManager().getEncryptionStrategy(strategyName);
        }

        if (strategy == null) {
            throw new InitialisationException(new org.mule.config.i18n.Message(Messages.ENCRYPT_STRATEGY_NOT_SET), this);
        }
    }

    public UMOEncryptionStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(UMOEncryptionStrategy strategy) {
        this.strategy = strategy;
    }

    public void setStrategyName(String name) {
        strategyName = name;
    }

}