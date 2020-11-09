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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.security.Credential;

/**
 * TestLoginService
 */
public class TestLoginService extends AbstractLoginService
{

    UserStore userStore = new UserStore();

    public TestLoginService(String name)
    {
        setName(name);
    }

    public void putUser(String username, Credential credential, String[] roles)
    {
        userStore.addUser(username, credential, roles);
    }

    @Override
    protected List<RolePrincipal> loadRoleInfo(UserPrincipal user)
    {
        return userStore.getRolePrincipals(user.getName());
    }

    /**
     * @see org.eclipse.jetty.security.AbstractLoginService#loadUserInfo(java.lang.String)
     */
    @Override
    protected UserPrincipal loadUserInfo(String username)
    {
        return userStore.getUserPrincipal(username);
    }
}
