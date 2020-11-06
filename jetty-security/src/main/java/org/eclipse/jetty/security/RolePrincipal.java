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

/**
 * RolePrincipal
 */
public class RolePrincipal implements Principal, Serializable
{
    private static final long serialVersionUID = 2998397924051854402L;
    private final String _roleName;

    public RolePrincipal(String name)
    {
        _roleName = name;
    }

    @Override
    public String getName()
    {
        return _roleName;
    }
    
    public void configureForSubject(Subject subject)
    {
        subject.getPrincipals().add(this);
    }
}