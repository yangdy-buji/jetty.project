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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.security.auth.Subject;

import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.security.Credential;

/**
 * Base class to store User
 */
public class UserStore extends AbstractLifeCycle
{
    private IdentityService _identityService = new DefaultIdentityService();
    private final Map<String, UserEntry> _users = new ConcurrentHashMap<>();
    
    protected class UserEntry
    {
        protected UserPrincipal _userPrincipal;
        protected List<RolePrincipal> _rolePrincipals;
        
        protected UserEntry(String username, Credential credential, String[] roles)
        {
            _userPrincipal = new UserPrincipal(username, credential);

            _rolePrincipals = Collections.emptyList();
            
            if (roles != null)
            {
                _rolePrincipals = Arrays.stream(roles).map(RolePrincipal::new).collect(Collectors.toList());
            }

        }
        
        protected UserPrincipal getUserPrincipal()
        {
            return _userPrincipal;
        }
        
        protected List<RolePrincipal> getRolePrincipals()
        {
            return _rolePrincipals;
        }
    }

    public void addUser(String username, Credential credential, String[] roles)
    {
        _users.put(username, new UserEntry(username, credential, roles));
    }

    public void removeUser(String username)
    {
        _users.remove(username);
    }
    
    public UserPrincipal getUserPrincipal(String username)
    {
        UserEntry user = _users.get(username);
        return (user == null ? null : user.getUserPrincipal());
    }
    
    public List<RolePrincipal> getRolePrincipals(String username)
    {
        UserEntry user = _users.get(username);
        return (user == null ? null : user.getRolePrincipals());
    }
}
