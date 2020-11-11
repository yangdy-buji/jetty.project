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
import java.util.stream.Collectors;

import org.eclipse.jetty.util.security.Credential;

class User
{
    protected UserPrincipal _userPrincipal;
    protected List<RolePrincipal> _rolePrincipals = Collections.emptyList();
    
    protected User(String username, Credential credential, String[] roles)
    {
        _userPrincipal = new UserPrincipal(username, credential);

        _rolePrincipals = Collections.emptyList();
        
        if (roles != null)
            _rolePrincipals = Arrays.stream(roles).map(RolePrincipal::new).collect(Collectors.toList());
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
