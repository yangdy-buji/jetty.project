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

import org.eclipse.jetty.util.security.Credential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class UserStoreTest
{
    UserStore userStore;

    @BeforeEach
    public void setup()
    {
        userStore = new UserStore();
    }

    @Test
    public void addUser()
    {
        userStore.addUser("foo", Credential.getCredential("beer"), new String[]{"pub"});
        assertNotNull(userStore.getUserPrincipal("foo"));

        List<RolePrincipal> rps = userStore.getRolePrincipals("foo");
        assertNotNull(rps);
        assertNotNull(rps.get(0));
        assertEquals("pub", rps.get(0).getName());
    }

    @Test
    public void removeUser()
    {
        this.userStore.addUser("foo", Credential.getCredential("beer"), new String[]{"pub"});
        assertNotNull(userStore.getUserPrincipal("foo"));
        userStore.removeUser("foo");
        assertNull(userStore.getUserPrincipal("foo"));
    }
}
