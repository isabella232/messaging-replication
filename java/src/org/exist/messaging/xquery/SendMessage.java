/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2012 The eXist Project
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

import org.exist.dom.QName;
import org.exist.xquery.*;
import org.exist.xquery.value.*;

/**
 *
 * @author wessels
 */


public class SendMessage extends BasicFunction {
    
 public final static FunctionSignature signatures[] = {

        new FunctionSignature(
            new QName("send", MessagingModule.NAMESPACE_URI, MessagingModule.PREFIX),
            "Send JMS message",
            new SequenceType[]{
                new FunctionParameterSequenceType("content", Type.ITEM, Cardinality.ONE, "Send message to remote server"),
                new FunctionParameterSequenceType("properties", Type.MAP,Cardinality.ZERO_OR_ONE, "Application-defined property values"),
                new FunctionParameterSequenceType("config", Type.MAP, Cardinality.ONE, "JMS configuration")
                
           
            },
            new FunctionReturnSequenceType(Type.NODE, Cardinality.ZERO_OR_ONE, "Confirmation message, if present")
        ),

        
    };

    public SendMessage(XQueryContext context, FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {
    
        throw new XPathException("Not implemented yet");
    
    }
    
}
