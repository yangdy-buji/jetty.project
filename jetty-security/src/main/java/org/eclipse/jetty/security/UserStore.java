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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.security.Credential;

/**
 * Store of user authentication and authorization information.
 * 
 */
public class UserStore extends AbstractLifeCycle
{
    protected final Map<String, User> _users = new ConcurrentHashMap<>();
    
    public void addUser(String username, Credential credential, String[] roles)
    {
        _users.put(username, new User(username, credential, roles));
    }

    public void removeUser(String username)
    {
        _users.remove(username);
    }
    
    public UserPrincipal getUserPrincipal(String username)
    {
        User user = _users.get(username);
        return (user == null ? null : user.getUserPrincipal());
    }
    
    public List<RolePrincipal> getRolePrincipals(String username)
    {
        User user = _users.get(username);
        return (user == null ? null : user.getRolePrincipals());
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[users.count=%d]", getClass().getSimpleName(), hashCode(), _users.size());
    }
}
