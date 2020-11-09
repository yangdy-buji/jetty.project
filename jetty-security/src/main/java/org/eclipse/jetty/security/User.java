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