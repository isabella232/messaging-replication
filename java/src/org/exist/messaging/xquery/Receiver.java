/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2013 The eXist Project
 *  http://exist-db.org
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 *  $Id$
 */
package org.exist.messaging.xquery;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Enumeration;
import java.util.Properties;

import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import javax.jms.TextMessage;

import javax.naming.Context;
import javax.naming.InitialContext;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.log4j.Logger;

import org.exist.Namespaces;
import org.exist.memtree.DocumentImpl;
import org.exist.memtree.SAXAdapter;
import org.exist.messaging.configuration.JmsConfiguration;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.validation.ValidationReport;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.functions.map.MapType;
import org.exist.xquery.value.Base64BinaryDocument;
import org.exist.xquery.value.BinaryValue;
import org.exist.xquery.value.BooleanValue;
import org.exist.xquery.value.DecimalValue;
import org.exist.xquery.value.DoubleValue;
import org.exist.xquery.value.FloatValue;
import org.exist.xquery.value.FunctionReference;
import org.exist.xquery.value.IntegerValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.StringValue;
import org.exist.xquery.value.ValueSequence;

import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

/**
 * JMS messages receiver.
 * 
 * Starts a JMS listener to receive messages from the broker
 * 
 * @author Dannes Wessels (dannes@exist-db.org)
 */
public class Receiver {

    private final static Logger LOG = Logger.getLogger(Receiver.class);
    
    private MyJMSListener myListener = new MyJMSListener();
    private FunctionReference ref;
    private JmsConfiguration config;
    private XQueryContext context;

    Receiver(FunctionReference ref, JmsConfiguration config, XQueryContext context) {
        this.ref = ref;
        this.config = config;
        this.context = context;
    }

    /**
     * Start listener
     */
    void start() {

        try {
            // Setup listener
            myListener.setFunctionReference(ref);
            myListener.setXQueryContext(context);

            // Setup Context
            Properties props = new Properties();
            props.setProperty(Context.INITIAL_CONTEXT_FACTORY, config.getInitialContextFactory());
            props.setProperty(Context.PROVIDER_URL, config.getProviderURL());
            Context initialContext = new InitialContext(props);

            // Setup connection
            ConnectionFactory connectionFactory = (ConnectionFactory) initialContext.lookup(config.getConnectionFactory());
            Connection connection = connectionFactory.createConnection();

            // Setup session
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            // Setup destination
            Destination destination = (Destination) initialContext.lookup(config.getDestination());

            // Setup consumer
            MessageConsumer messageConsumer = session.createConsumer(destination);
            messageConsumer.setMessageListener(myListener);

            // Start listener
            connection.start();

            LOG.info("Receiver is ready");

        } catch (Throwable t) {
            LOG.error(t.getMessage(), t);
        }

    }

    private static class MyJMSListener implements MessageListener {

        private FunctionReference functionReference;
        private XQueryContext xqueryContext;
        
        public MyJMSListener(){
            // NOP
            
        }

        public MyJMSListener(FunctionReference functionReference, XQueryContext xqueryContext) {
            super();
            this.functionReference=functionReference;
            this.xqueryContext=xqueryContext;
        }
        
        @Override
        public void onMessage(Message msg) {
            
            // Log incoming message
            try {
                LOG.info(String.format("Received message: ID=%s JavaType=", msg.getJMSMessageID(), msg.getClass().getSimpleName()));
            } catch (JMSException ex) {
                LOG.error(ex.getMessage());
            }
            
            // Placeholder for broker
            DBBroker dummyBroker=null;
            
            try {
                /*
                 * Work around to to have a broker available for the
                 * execution of #evalFunction. In the onMessage() method this
                 * broker is not being used at all.
                 */
                BrokerPool bp = BrokerPool.getInstance();
                dummyBroker=bp.getBroker();
                   
                // Copy message and jms configuration details into Maptypes
                MapType msgProperties = getMessageProperties(msg, xqueryContext);
                MapType jmsProperties = getJmsProperties(msg, xqueryContext);

                // This sequence shall contain the actual conten that will be passed
                // to the callback function
                Sequence content = null;
                
                // Switch based on incoming
                if (msg instanceof TextMessage) {
                    
                    // xs:string values are passed as regular Text messages
                    content = new StringValue(((TextMessage) msg).getText());

                } else if (msg instanceof ObjectMessage) {
                    
                    // the supported other types are wrapped into a corresponding
                    // Java object inside the ObjectMessage
                    Object obj = ((ObjectMessage) msg).getObject();

                    if (obj instanceof BigInteger) {
                        content = new IntegerValue((BigInteger) obj);

                    } else if (obj instanceof Double) {
                        content = new DoubleValue((Double) obj);

                    } else if (obj instanceof BigDecimal) {
                        content = new DecimalValue((BigDecimal) obj);

                    } else if (obj instanceof Boolean) {
                        content = new BooleanValue((Boolean) obj);

                    } else if (obj instanceof Float) {
                        content = new FloatValue((Float) obj);

                    } else {
                        String txt=String.format("Unable to convert the object %s", obj.toString());
                        LOG.error(txt);
                        throw new XPathException(txt);
                    }
                
                } else if(msg instanceof BytesMessage){
                    
                    // XML nodes and base64 (binary) data are sent as an array of bytes
                    BytesMessage bm = (BytesMessage) msg;
                    
                    // Read data into byte buffer
                    byte[] data = new byte[(int) bm.getBodyLength()];
                    bm.readBytes(data);
                    
                    // if XML(fragment)
                    if ("xml".equalsIgnoreCase(bm.getStringProperty("exist.data.type"))) {
                        
                        // XML fragment
                        ByteArrayInputStream bais = new ByteArrayInputStream(data);

                        final ValidationReport report = new ValidationReport();
                        final SAXAdapter adapter = new SAXAdapter(xqueryContext);

                        final SAXParserFactory factory = SAXParserFactory.newInstance();
                        factory.setNamespaceAware(true);
                        final InputSource src = new InputSource(bais);

                        final SAXParser parser = factory.newSAXParser();
                        XMLReader xr = parser.getXMLReader();

                        xr.setErrorHandler(report);
                        xr.setContentHandler(adapter);
                        xr.setProperty(Namespaces.SAX_LEXICAL_HANDLER, adapter);
                        xr.parse(src);

                        if (report.isValid()){
                            //content=new NodeProxy(doc.);
                            content = (DocumentImpl) adapter.getDocument();
                        } else {
                            String txt = "Received document is not valid: " + report.toString();
                            LOG.debug(txt);
                            throw new XPathException(txt);
                        }

                    } else {
                        // Binary data
                        ByteArrayInputStream bais = new ByteArrayInputStream(data);                        
                        BinaryValue bv = Base64BinaryDocument.getInstance(xqueryContext, bais);
                        content=bv;
                    }
                    
                    
                } else {
                    // Unsupported JMS message type
                    String txt = "Unsupported JMS Message type " + msg.getClass().getCanonicalName();
                    LOG.error(txt);
                    throw new XPathException(txt);
                }
                
                // Setup parameters callback function
                Sequence params[] = new Sequence[3];
                params[0] = content;
                params[1] = msgProperties;
                params[2] = jmsProperties;
                
                // Execute callback function
                Sequence result = functionReference.evalFunction(null, null, params);
                
                // Done
                if(LOG.isDebugEnabled()){
                    LOG.debug(String.format("Function returned%s", result.getStringValue()));
                }

            } catch (JMSException ex) {
                LOG.error(ex.getMessage(), ex);
                ex.printStackTrace();

            } catch (XPathException ex) {
                LOG.error(ex);
                ex.printStackTrace();

            } catch (Throwable ex) {
                LOG.error(ex.getMessage(), ex);
                ex.printStackTrace();
                
            } finally {
                // Cleanup resources
                if(dummyBroker!=null){
                    dummyBroker.release();
                }  
            }

        }

        public void setFunctionReference(FunctionReference ref) {
            this.functionReference = ref;
        }
        
        public void setXQueryContext(XQueryContext context) {
            this.xqueryContext = context;
        }
        
        private MapType getMessageProperties(Message msg, XQueryContext xqueryContext) throws XPathException, JMSException {
            // Copy property values into Maptype
            MapType map = new MapType(xqueryContext);

            Enumeration props = msg.getPropertyNames();
            while (props.hasMoreElements()) {
                String elt = (String) props.nextElement();
                String value = msg.getStringProperty(elt);
                add(map, elt, value);
            }
            return map;
        }
        
        private MapType getJmsProperties(Message msg, XQueryContext xqueryContext) throws XPathException, JMSException {
            // Copy property values into Maptype
            MapType map = new MapType(xqueryContext);

            add(map, "JMSMessageID", msg.getJMSMessageID());
            add(map, "JMSCorrelationID", msg.getJMSCorrelationID());
            add(map, "JMSType", msg.getJMSType());
            add(map, "JMSPriority", ""+msg.getJMSPriority());
            add(map, "JMSExpiration", ""+msg.getJMSExpiration());
            add(map, "JMSTimestamp", ""+msg.getJMSTimestamp());
            
            return map;
        }
        
        private void add(MapType map, String key, String value) throws XPathException {
            if (map != null && key != null && !key.isEmpty() && value != null) {
                map.add(new StringValue(key), new ValueSequence(new StringValue(value)));
            }
        }
        
    }
}
