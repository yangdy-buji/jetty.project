//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.deploy.bindings;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;

public class StandardDeployer implements AppLifeCycle.Binding
{
    @Override
    public String[] getBindingTargets()
    {
        return new String[]{"deploying"};
    }

    @Override
    public void processBinding(Node node, App app) throws Exception
    {
        ContextHandler handler = app.getContextHandler();
        if (handler == null)
            throw new NullPointerException("No Handler created for App: " + app);

        Callback.Completable blocker = new Callback.Completable();
        app.getDeploymentManager().getContexts().deployHandler(handler, blocker);
        blocker.get();
    }
}
