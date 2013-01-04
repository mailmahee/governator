/*
 * Copyright 2013 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.governator.lifecycle.warmup;

import com.google.common.collect.Lists;
import com.netflix.governator.annotations.WarmUp;
import com.netflix.governator.lifecycle.LifecycleManager;
import com.netflix.governator.lifecycle.LifecycleMethods;
import com.netflix.governator.lifecycle.LifecycleState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import jsr166y.RecursiveAction;

/**
 * <p>
 *     A Fork Join task to warm up objects. Each node's dependency's
 *     are warmed-up via forking before the node itself warms-up.
 * </p>
 *
 * <p>
 *     Thanks to Allan Pratt for his help and design ideas
 * </p>
 */
public class WarmUpTask extends RecursiveAction
{
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final LifecycleManager lifecycleManager;
    private final ConcurrentMap<Object, WarmUpTask> tasks;
    private final boolean isRoot;
    private final WarmUpErrors errors;
    private final DependencyNode node;

    /**
     * @param lifecycleManager lifecycle manager
     * @param errors container for warm up errors
     * @param node the node to warm up
     * @param isRoot true if the node is the root node (don't try to warm it up)
     */
    public WarmUpTask(LifecycleManager lifecycleManager, WarmUpErrors errors, DependencyNode node, ConcurrentMap<Object, WarmUpTask> tasks, boolean isRoot)
    {
        this.errors = errors;
        this.node = node;
        this.lifecycleManager = lifecycleManager;
        this.tasks = tasks;
        this.isRoot = isRoot;
    }

    @Override
    protected void compute()
    {
        List<WarmUpTask> childTasks = Lists.newArrayList();
        for ( DependencyNode child : node.getChildren() )
        {
            WarmUpTask  newChildTask = new WarmUpTask(lifecycleManager, errors, child, tasks, false);
            WarmUpTask  existingChildTask = tasks.putIfAbsent(child.getKey(), newChildTask);
            if ( existingChildTask == null )
            {
                newChildTask.fork();
                childTasks.add(newChildTask);
            }
            else
            {
                childTasks.add(existingChildTask);
            }
        }

        for ( WarmUpTask task : childTasks )
        {
            task.join();
        }

        if ( !isRoot )
        {
            warmUpObject();
        }
    }

    private void warmUpObject()
    {
        Object                  obj = lifecycleManager.getDAGManager().getObject(node.getKey());
        if ( obj == null )
        {
            log.error(String.format("Could not find lifecycle-registered object for key. Ignoring object. Key: %s - KeyClass: %s", node.getKey(), node.getKey().getClass().getName()));
        }
        else
        {
            lifecycleManager.setState(obj, LifecycleState.WARMING_UP);

            LifecycleMethods    lifecycleMethods = lifecycleManager.getDAGManager().getLifecycleMethods(node.getKey());
            Collection<Method>  methods = (lifecycleMethods != null) ? lifecycleMethods.methodsFor(WarmUp.class) : null;

            LifecycleState      newState = LifecycleState.ACTIVE;
            try
            {
                if ( methods != null )
                {
                    for ( Method method : methods )
                    {
                        method.invoke(obj);
                    }
                }
            }
            catch ( Throwable e )
            {
                String      message = String.format("Key: %s - Object: %s", node.getKey(), obj.getClass().getName());
                e = errors.addError(e, message);
                log.error("Error warming up object. " + message, e);
                newState = LifecycleState.ERROR;
            }
            finally
            {
                lifecycleManager.setState(obj, newState);
            }
        }
    }
}
