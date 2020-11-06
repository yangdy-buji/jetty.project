//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.security;

import java.io.Serializable;
import java.security.Principal;
import javax.security.auth.Subject;

import org.eclipse.jetty.util.security.Credential;

/**
 * UserPrincipal
 */
public class UserPrincipal implements Principal, Serializable
{
    private static final long serialVersionUID = -6226920753748399662L;
    private final String _name;
    protected final Credential _credential;

    public UserPrincipal(String name, Credential credential)
    {
        _name = name;
        _credential = credential;
    }

    public boolean authenticate(Object credentials)
    {
        return _credential != null && _credential.check(credentials);
    }

    public boolean authenticate(Credential c)
    {
        return (_credential != null && c != null && _credential.equals(c));
    }
    
    public boolean authenticate(UserPrincipal u)
    {
        return (u != null && authenticate(u._credential));
    }

    public void configureForSubject(Subject subject)
    {
        subject.getPrincipals().add(this);
        subject.getPrivateCredentials().add(_credential); 
    }
    
    @Override
    public String getName()
    {
        return _name;
    }

    @Override
    public String toString()
    {
        return _name;
    }
}