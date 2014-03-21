/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.configuration.tree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.configuration.ex.ConfigurationRuntimeException;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;

/**
 * <p>
 * A specialized node model implementation which operates on
 * {@link ImmutableNode} structures.
 * </p>
 * <p>
 * This {@code NodeModel} implementation keeps all its data as a tree of
 * {@link ImmutableNode} objects in memory. The managed structure can be
 * manipulated in a thread-safe, non-blocking way. This is achieved by using
 * atomic variables: The root of the tree is stored in an atomic reference
 * variable. Each update operation causes a new structure to be constructed
 * (which reuses as much from the original structure as possible). The old root
 * node is then replaced by the new one using an atomic compare-and-set
 * operation. If this fails, the manipulation has to be done anew on the updated
 * structure.
 * </p>
 *
 * @version $Id$
 * @since 2.0
 */
public class InMemoryNodeModel implements NodeModel<ImmutableNode>
{
    /**
     * A dummy node handler instance used in operations which require only a
     * limited functionality.
     */
    private static final NodeHandler<ImmutableNode> DUMMY_HANDLER =
            new TreeData(null,
                    Collections.<ImmutableNode, ImmutableNode> emptyMap(),
                    Collections.<ImmutableNode, ImmutableNode> emptyMap(), null);

    /** Stores information about the current nodes structure. */
    private final AtomicReference<TreeData> structure;

    /**
     * Creates a new instance of {@code InMemoryNodeModel} which is initialized
     * with an empty root node.
     */
    public InMemoryNodeModel()
    {
        this(null);
    }

    /**
     * Creates a new instance of {@code InMemoryNodeModel} and initializes it
     * from the given root node. If the passed in node is <b>null</b>, a new,
     * empty root node is created.
     *
     * @param root the new root node for this model
     */
    public InMemoryNodeModel(ImmutableNode root)
    {
        structure =
                new AtomicReference<TreeData>(
                        createTreeData(initialRootNode(root), null));
    }

    /**
     * Returns the root node of this mode.
     *
     * @return the current root node
     */
    public ImmutableNode getRootNode()
    {
        return getTreeData().getRootNode();
    }

    /**
     * {@inheritDoc} {@code InMemoryNodeModel} implements the
     * {@code NodeHandler} interface itself. So this implementation just returns
     * the <strong>this</strong> reference.
     */
    public NodeHandler<ImmutableNode> getNodeHandler()
    {
        return getTreeData();
    }

    public void addProperty(String key, Iterable<?> values,
            NodeKeyResolver<ImmutableNode> resolver)
    {
        addProperty(key, null, values, resolver);
    }

    /**
     * Adds new property values using a tracked node as root node. This method
     * works like the normal {@code addProperty()} method, but the origin of the
     * operation (also for the interpretation of the passed in key) is a tracked
     * node identified by the passed in {@code NodeSelector}. The selector can
     * be <b>null</b>, then the root node is assumed.
     *
     * @param key the key
     * @param selector the {@code NodeSelector} defining the root node (or
     *        <b>null</b>)
     * @param resolver the {@code NodeKeyResolver}
     * @throws ConfigurationRuntimeException if the selector cannot be resolved
     */
    public void addProperty(final String key, NodeSelector selector,
            final Iterable<?> values,
            final NodeKeyResolver<ImmutableNode> resolver)
    {
        if (valuesNotEmpty(values))
        {
            updateModel(new TransactionInitializer()
            {
                public boolean initTransaction(ModelTransaction tx)
                {
                    initializeAddTransaction(tx, key, values, resolver);
                    return true;
                }
            }, selector, resolver);
        }
    }

    public void addNodes(String key, Collection<? extends ImmutableNode> nodes,
            NodeKeyResolver<ImmutableNode> resolver)
    {
        addNodes(key, null, nodes, resolver);
    }

    /**
     * Adds new nodes using a tracked node as root node. This method works like
     * the normal {@code addNodes()} method, but the origin of the operation
     * (also for the interpretation of the passed in key) is a tracked node
     * identified by the passed in {@code NodeSelector}. The selector can be
     * <b>null</b>, then the root node is assumed.
     *
     * @param key the key
     * @param selector the {@code NodeSelector} defining the root node (or
     *        <b>null</b>)
     * @param nodes the collection of new nodes to be added
     * @param resolver the {@code NodeKeyResolver}
     * @throws ConfigurationRuntimeException if the selector cannot be resolved
     */
    public void addNodes(final String key, NodeSelector selector,
            final Collection<? extends ImmutableNode> nodes,
            final NodeKeyResolver<ImmutableNode> resolver)
    {
        if (nodes != null && !nodes.isEmpty())
        {
            updateModel(new TransactionInitializer()
            {
                public boolean initTransaction(ModelTransaction tx)
                {
                    List<QueryResult<ImmutableNode>> results =
                            resolver.resolveKey(tx.getQueryRoot(), key,
                                    tx.getCurrentData());
                    if (results.size() == 1)
                    {
                        if (results.get(0).isAttributeResult())
                        {
                            throw attributeKeyException(key);
                        }
                        tx.addAddNodesOperation(results.get(0).getNode(), nodes);
                    }
                    else
                    {
                        NodeAddData<ImmutableNode> addData =
                                resolver.resolveAddKey(tx.getQueryRoot(), key,
                                        tx.getCurrentData());
                        if (addData.isAttribute())
                        {
                            throw attributeKeyException(key);
                        }
                        ImmutableNode newNode =
                                new ImmutableNode.Builder(nodes.size())
                                        .name(addData.getNewNodeName())
                                        .addChildren(nodes).create();
                        addNodesByAddData(tx, addData,
                                Collections.singleton(newNode));
                    }
                    return true;
                }
            }, selector, resolver);
        }
    }

    public void setProperty(String key, Object value,
            NodeKeyResolver<ImmutableNode> resolver)
    {
        setProperty(key, null, value, resolver);
    }

    /**
     * Sets the value of a property using a tracked node as root node. This
     * method works like the normal {@code setProperty()} method, but the origin
     * of the operation (also for the interpretation of the passed in key) is a
     * tracked node identified by the passed in {@code NodeSelector}. The
     * selector can be <b>null</b>, then the root node is assumed.
     *
     * @param key the key
     * @param selector the {@code NodeSelector} defining the root node (or
     *        <b>null</b>)
     * @param value the new value for this property
     * @param resolver the {@code NodeKeyResolver}
     * @throws ConfigurationRuntimeException if the selector cannot be resolved
     */
    public void setProperty(final String key, NodeSelector selector,
            final Object value, final NodeKeyResolver<ImmutableNode> resolver)
    {
        updateModel(new TransactionInitializer()
        {
            public boolean initTransaction(ModelTransaction tx)
            {
                boolean added = false;
                NodeUpdateData<ImmutableNode> updateData =
                        resolver.resolveUpdateKey(tx.getQueryRoot(), key,
                                value, tx.getCurrentData());
                if (!updateData.getNewValues().isEmpty())
                {
                    initializeAddTransaction(tx, key,
                            updateData.getNewValues(), resolver);
                    added = true;
                }
                boolean cleared =
                        initializeClearTransaction(tx,
                                updateData.getRemovedNodes());
                boolean updated =
                        initializeUpdateTransaction(tx,
                                updateData.getChangedValues());
                return added || cleared || updated;
            }
        }, selector, resolver);
    }

    /**
     * {@inheritDoc} This implementation checks whether nodes become undefined
     * after subtrees have been removed. If this is the case, such nodes are
     * removed, too.
     */
    public void clearTree(String key, NodeKeyResolver<ImmutableNode> resolver)
    {
        clearTree(key, null, resolver);
    }

    /**
     * Clears a whole sub tree using a tracked node as root node. This method
     * works like the normal {@code clearTree()} method, but the origin of the
     * operation (also for the interpretation of the passed in key) is a tracked
     * node identified by the passed in {@code NodeSelector}. The selector can
     * be <b>null</b>, then the root node is assumed.
     *
     * @param key the key
     * @param selector the {@code NodeSelector} defining the root node (or
     *        <b>null</b>)
     * @param resolver the {@code NodeKeyResolver}
     * @throws ConfigurationRuntimeException if the selector cannot be resolved
     */
    public void clearTree(final String key, NodeSelector selector,
            final NodeKeyResolver<ImmutableNode> resolver)
    {
        updateModel(new TransactionInitializer()
        {
            public boolean initTransaction(ModelTransaction tx)
            {
                TreeData currentStructure = tx.getCurrentData();
                for (QueryResult<ImmutableNode> result : resolver.resolveKey(
                        tx.getQueryRoot(), key, currentStructure))
                {
                    if (result.isAttributeResult())
                    {
                        tx.addRemoveAttributeOperation(result.getNode(),
                                result.getAttributeName());
                    }
                    else
                    {
                        if (result.getNode() == currentStructure.getRootNode())
                        {
                            // the whole model is to be cleared
                            clear(resolver);
                            return false;
                        }
                        tx.addRemoveNodeOperation(
                                currentStructure.getParent(result.getNode()),
                                result.getNode());
                    }
                }
                return true;
            }
        }, selector, resolver);
    }

    /**
     * {@inheritDoc} If this operation leaves an affected node in an undefined
     * state, it is removed from the model.
     */
    public void clearProperty(String key,
            NodeKeyResolver<ImmutableNode> resolver)
    {
        clearProperty(key, null, resolver);
    }

    /**
     * Clears a property using a tracked node as root node. This method works
     * like the normal {@code clearProperty()} method, but the origin of the
     * operation (also for the interpretation of the passed in key) is a tracked
     * node identified by the passed in {@code NodeSelector}. The selector can
     * be <b>null</b>, then the root node is assumed.
     *
     * @param key the key
     * @param selector the {@code NodeSelector} defining the root node (or
     *        <b>null</b>)
     * @param resolver the {@code NodeKeyResolver}
     * @throws ConfigurationRuntimeException if the selector cannot be resolved
     */
    public void clearProperty(final String key, NodeSelector selector,
            final NodeKeyResolver<ImmutableNode> resolver)
    {
        updateModel(new TransactionInitializer() {
            public boolean initTransaction(ModelTransaction tx) {
                List<QueryResult<ImmutableNode>> results =
                        resolver.resolveKey(tx.getQueryRoot(), key,
                                tx.getCurrentData());
                initializeClearTransaction(tx, results);
                return true;
            }
        }, selector, resolver);
    }

    /**
     * {@inheritDoc} A new empty root node is created with the same name as the
     * current root node. Implementation note: Because this is a hard reset the
     * usual dance for dealing with concurrent updates is not required here.
     *
     * @param resolver the {@code NodeKeyResolver}
     */
    public void clear(NodeKeyResolver<ImmutableNode> resolver)
    {
        ImmutableNode newRoot =
                new ImmutableNode.Builder().name(getRootNode().getNodeName())
                        .create();
        setRootNode(newRoot);
    }

    /**
     * {@inheritDoc} Care has to be taken when this method is used and the model
     * is accessed by multiple threads. It is not deterministic which concurrent
     * operations see the old root and which see the new root node.
     *
     * @param newRoot the new root node to be set (can be <b>null</b>, then an
     *        empty root node is set)
     */
    public void setRootNode(ImmutableNode newRoot)
    {
        structure.set(createTreeData(initialRootNode(newRoot), structure.get()));
    }

    /**
     * Adds a node to be tracked. After this method has been called with a
     * specific {@code NodeSelector}, the node associated with this key can be
     * always obtained using {@link #getTrackedNode(NodeSelector)} with the same
     * selector. This is useful because during updates of a model parts of the
     * structure are replaced. Therefore, it is not a good idea to simply hold a
     * reference to a node; this might become outdated soon. Rather, the node
     * should be tracked. This mechanism ensures that always the correct node
     * reference can be obtained.
     *
     * @param selector the {@code NodeSelector} defining the desired node
     * @param resolver the {@code NodeKeyResolver}
     * @throws ConfigurationRuntimeException if the selector does not select a
     *         single node
     */
    public void trackNode(NodeSelector selector,
            NodeKeyResolver<ImmutableNode> resolver)
    {
        boolean done;
        do
        {
            TreeData current = structure.get();
            NodeTracker newTracker =
                    current.getNodeTracker().trackNode(current.getRootNode(),
                            selector, resolver, current);
            done =
                    structure.compareAndSet(current,
                            current.updateNodeTracker(newTracker));
        } while (!done);
    }

    /**
     * Allows tracking all nodes selected by a key. This method evaluates the
     * specified key on the current nodes structure. For all selected nodes
     * corresponding {@code NodeSelector} objects are created, and they are
     * tracked. The returned collection of {@code NodeSelector} objects can be
     * used for interacting with the selected nodes.
     *
     * @param key the key for selecting the nodes to track
     * @param resolver the {@code NodeKeyResolver}
     * @return a collection with the {@code NodeSelector} objects for the new
     *         tracked nodes
     */
    public Collection<NodeSelector> selectAndTrackNodes(String key,
            NodeKeyResolver<ImmutableNode> resolver)
    {
        Mutable<Collection<NodeSelector>> refSelectors =
                new MutableObject<Collection<NodeSelector>>();
        boolean done;
        do
        {
            TreeData current = structure.get();
            List<ImmutableNode> nodes =
                    resolver.resolveNodeKey(current.getRootNode(), key, current);
            if (nodes.isEmpty())
            {
                return Collections.emptyList();
            }
            done =
                    structure.compareAndSet(
                            current,
                            createSelectorsForTrackedNodes(refSelectors, nodes,
                                    current, resolver));
        } while (!done);
        return refSelectors.getValue();
    }

    /**
     * Tracks all nodes which are children of the node selected by the passed in
     * key. If the key selects exactly one node, for all children of this node
     * {@code NodeSelector} objects are created, and they become tracked nodes.
     * The returned collection of {@code NodeSelector} objects can be used for
     * interacting with the selected nodes.
     *
     * @param key the key for selecting the parent node whose children are to be
     *        tracked
     * @param resolver the {@code NodeKeyResolver}
     * @return a collection with the {@code NodeSelector} objects for the new
     *         tracked nodes
     */
    public Collection<NodeSelector> trackChildNodes(String key,
            NodeKeyResolver<ImmutableNode> resolver)
    {
        Mutable<Collection<NodeSelector>> refSelectors =
                new MutableObject<Collection<NodeSelector>>();
        boolean done;
        do
        {
            refSelectors.setValue(Collections.<NodeSelector> emptyList());
            TreeData current = structure.get();
            List<ImmutableNode> nodes =
                    resolver.resolveNodeKey(current.getRootNode(), key, current);
            if (nodes.size() == 1)
            {
                ImmutableNode node = nodes.get(0);
                done =
                        node.getChildren().isEmpty()
                                || structure.compareAndSet(
                                        current,
                                        createSelectorsForTrackedNodes(
                                                refSelectors,
                                                node.getChildren(), current,
                                                resolver));
            }
            else
            {
                done = true;
            }
        } while (!done);
        return refSelectors.getValue();
    }

    /**
     * Returns the current {@code ImmutableNode} instance associated with the
     * given {@code NodeSelector}. The node must be a tracked node, i.e.
     * {@link #trackNode(NodeSelector, NodeKeyResolver)} must have been called
     * before with the given selector.
     *
     * @param selector the {@code NodeSelector} defining the desired node
     * @return the current {@code ImmutableNode} associated with this selector
     * @throws ConfigurationRuntimeException if the selector is unknown
     */
    public ImmutableNode getTrackedNode(NodeSelector selector)
    {
        return structure.get().getNodeTracker().getTrackedNode(selector);
    }

    /**
     * Replaces a tracked node by another node. If the tracked node is not yet
     * detached, it becomes now detached. The passed in node (which must not be
     * <b>null</b>) becomes the new root node of an independent model for this
     * tracked node. Further updates of this model do not affect the tracked
     * node's model and vice versa.
     *
     * @param selector the {@code NodeSelector} defining the tracked node
     * @param newNode the node replacing the tracked node (must not be
     *        <b>null</b>)
     * @throws ConfigurationRuntimeException if the selector cannot be resolved
     * @throws IllegalArgumentException if the replacement node is <b>null</b>
     */
    public void replaceTrackedNode(NodeSelector selector, ImmutableNode newNode)
    {
        if (newNode == null)
        {
            throw new IllegalArgumentException(
                    "Replacement node must not be null!");
        }

        boolean done;
        do
        {
            TreeData currentData = structure.get();
            done =
                    replaceDetachedTrackedNode(currentData, selector, newNode)
                            || replaceActiveTrackedNode(currentData, selector,
                                    newNode);
        } while (!done);
    }

    /**
     * Returns a {@code NodeHandler} for a tracked node. Such a handler may be
     * required for operations on a sub tree of the model. The handler to be
     * returned depends on the current state of the tracked node. If it is still
     * active, a handler is used which shares some data (especially the parent
     * mapping) with this model. Detached track nodes in contrast have their own
     * separate model; in this case a handler associated with this model is
     * returned.
     *
     * @param selector the {@code NodeSelector} defining the tracked node
     * @return a {@code NodeHandler} for this tracked node
     * @throws ConfigurationRuntimeException if the selector is unknown
     */
    public NodeHandler<ImmutableNode> getTrackedNodeHandler(
            NodeSelector selector)
    {
        TreeData currentData = structure.get();
        InMemoryNodeModel detachedNodeModel =
                currentData.getNodeTracker().getDetachedNodeModel(selector);
        return (detachedNodeModel != null) ? detachedNodeModel.getNodeHandler()
                : new TrackedNodeHandler(currentData.getNodeTracker()
                        .getTrackedNode(selector), currentData);
    }

    /**
     * Returns a flag whether the specified tracked node is detached. As long as
     * the {@code NodeSelector} associated with that node returns a single
     * instance, the tracked node is said to be <em>life</em>. If now an update
     * of the model happens which invalidates the selector (maybe the target
     * node was removed), the tracked node becomes detached. It is still
     * possible to query the node; here the latest valid instance is returned.
     * But further changes on the node model are no longer tracked for this
     * node. So even if there are further changes which would make the
     * {@code NodeSelector} valid again, the tracked node stays in detached
     * state.
     *
     * @param selector the {@code NodeSelector} defining the desired node
     * @return a flag whether this tracked node is in detached state
     * @throws ConfigurationRuntimeException if the selector is unknown
     */
    public boolean isTrackedNodeDetached(NodeSelector selector)
    {
        return structure.get().getNodeTracker().isTrackedNodeDetached(selector);
    }

    /**
     * Removes a tracked node. This method is the opposite of
     * {@code trackNode()}. It has to be called if there is no longer the need
     * to track a specific node. Note that for each call of {@code trackNode()}
     * there has to be a corresponding {@code untrackNode()} call. This ensures
     * that multiple observers can track the same node.
     *
     * @param selector the {@code NodeSelector} defining the desired node
     * @throws ConfigurationRuntimeException if the specified node is not
     *         tracked
     */
    public void untrackNode(NodeSelector selector)
    {
        boolean done;
        do
        {
            TreeData current = structure.get();
            NodeTracker newTracker =
                    current.getNodeTracker().untrackNode(selector);
            done =
                    structure.compareAndSet(current,
                            current.updateNodeTracker(newTracker));
        } while (!done);
    }

    /**
     * Returns the current {@code TreeData} object. This object contains all
     * information about the current node structure.
     *
     * @return the current {@code TreeData} object
     */
    TreeData getTreeData()
    {
        return structure.get();
    }

    /**
     * Updates the mapping from nodes to their parents for the passed in
     * hierarchy of nodes. This method traverses all children and grand-children
     * of the passed in root node. For each node in the subtree the parent
     * relation is added to the map.
     *
     * @param parents the map with parent nodes
     * @param root the root node of the current tree
     */
    static void updateParentMapping(final Map<ImmutableNode, ImmutableNode> parents,
            ImmutableNode root)
    {
        NodeTreeWalker.INSTANCE.walkBFS(root,
                new ConfigurationNodeVisitorAdapter<ImmutableNode>()
                {
                    public void visitBeforeChildren(ImmutableNode node,
                            NodeHandler<ImmutableNode> handler)
                    {
                        for (ImmutableNode c : node.getChildren())
                        {
                            parents.put(c, node);
                        }
                    }
                }, DUMMY_HANDLER);
    }

    /**
     * Checks if the passed in node is defined. Result is <b>true</b> if the
     * node contains any data.
     *
     * @param node the node in question
     * @return <b>true</b> if the node is defined, <b>false</b> otherwise
     */
    static boolean checkIfNodeDefined(ImmutableNode node)
    {
        return node.getValue() != null || !node.getChildren().isEmpty()
                || !node.getAttributes().isEmpty();
    }

    /**
     * Initializes a transaction for an add operation.
     *
     * @param tx the transaction to be initialized
     * @param key the key
     * @param values the collection with node values
     * @param resolver the {@code NodeKeyResolver}
     */
    private void initializeAddTransaction(ModelTransaction tx, String key,
            Iterable<?> values, NodeKeyResolver<ImmutableNode> resolver)
    {
        NodeAddData<ImmutableNode> addData =
                resolver.resolveAddKey(tx.getQueryRoot(), key,
                        tx.getCurrentData());
        if (addData.isAttribute())
        {
            addAttributeProperty(tx, addData, values);
        }
        else
        {
            addNodeProperty(tx, addData, values);
        }
    }

    /**
     * Creates a {@code TreeData} object for the specified root node.
     *
     * @param root the root node of the current tree
     * @param current the current {@code TreeData} object (may be <b>null</b>)
     * @return the {@code TreeData} describing the current tree
     */
    private TreeData createTreeData(ImmutableNode root, TreeData current)
    {
        NodeTracker newTracker =
                (current != null) ? current.getNodeTracker()
                        .detachAllTrackedNodes() : new NodeTracker();
        return new TreeData(root, createParentMapping(root),
                Collections.<ImmutableNode, ImmutableNode> emptyMap(),
                newTracker);
    }

    /**
     * Handles an add property operation if the property to be added is a node.
     *
     * @param tx the transaction
     * @param addData the {@code NodeAddData}
     * @param values the collection with node values
     */
    private static void addNodeProperty(ModelTransaction tx,
            NodeAddData<ImmutableNode> addData, Iterable<?> values)
    {
        Collection<ImmutableNode> newNodes =
                createNodesToAdd(addData.getNewNodeName(), values);
        addNodesByAddData(tx, addData, newNodes);
    }

    /**
     * Initializes a transaction to add a collection of nodes as described by a
     * {@code NodeAddData} object. If necessary, new path nodes are created.
     * Eventually, the new nodes are added as children to the specified target
     * node.
     *
     * @param tx the transaction
     * @param addData the {@code NodeAddData}
     * @param newNodes the collection of new child nodes
     */
    private static void addNodesByAddData(ModelTransaction tx,
            NodeAddData<ImmutableNode> addData,
            Collection<ImmutableNode> newNodes)
    {
        if (addData.getPathNodes().isEmpty())
        {
            tx.addAddNodesOperation(addData.getParent(), newNodes);
        }
        else
        {
            ImmutableNode newChild = createNodeToAddWithPath(addData, newNodes);
            tx.addAddNodeOperation(addData.getParent(), newChild);
        }
    }

    /**
     * Handles an add property operation if the property to be added is an
     * attribute.
     *
     * @param tx the transaction
     * @param addData the {@code NodeAddData}
     * @param values the collection with node values
     */
    private static void addAttributeProperty(ModelTransaction tx,
            NodeAddData<ImmutableNode> addData, Iterable<?> values)
    {
        if (addData.getPathNodes().isEmpty())
        {
            tx.addAttributeOperation(addData.getParent(),
                    addData.getNewNodeName(), values.iterator().next());
        }
        else
        {
            int pathNodeCount = addData.getPathNodes().size();
            ImmutableNode childWithAttribute =
                    new ImmutableNode.Builder()
                            .name(addData.getPathNodes().get(pathNodeCount - 1))
                            .addAttribute(addData.getNewNodeName(),
                                    values.iterator().next()).create();
            ImmutableNode newChild =
                    (pathNodeCount > 1) ? createNodeOnPath(addData
                            .getPathNodes().subList(0, pathNodeCount - 1)
                            .iterator(),
                            Collections.singleton(childWithAttribute))
                            : childWithAttribute;
            tx.addAddNodeOperation(addData.getParent(), newChild);
        }
    }

    /**
     * Creates a collection with new nodes with a given name and a value from a
     * given collection.
     *
     * @param newNodeName the name of the new nodes
     * @param values the collection with node values
     * @return the newly created collection
     */
    private static Collection<ImmutableNode> createNodesToAdd(
            String newNodeName, Iterable<?> values)
    {
        Collection<ImmutableNode> nodes = new LinkedList<ImmutableNode>();
        for (Object value : values)
        {
            nodes.add(new ImmutableNode.Builder().name(newNodeName)
                    .value(value).create());
        }
        return nodes;
    }

    /**
     * Creates a node structure consisting of the path nodes defined by the
     * passed in {@code NodeAddData} instance and all new child nodes.
     *
     * @param addData the {@code NodeAddData}
     * @param newNodes the collection of new child nodes
     * @return the parent node of the newly created hierarchy
     */
    private static ImmutableNode createNodeToAddWithPath(
            NodeAddData<ImmutableNode> addData,
            Collection<ImmutableNode> newNodes)
    {
        return createNodeOnPath(addData.getPathNodes().iterator(), newNodes);
    }

    /**
     * Recursive helper method for creating a path node for an add operation.
     * All path nodes except for the last have a single child. The last path
     * node has the new nodes as children.
     *
     * @param it the iterator over the names of the path nodes
     * @param newNodes the collection of new child nodes
     * @return the newly created path node
     */
    private static ImmutableNode createNodeOnPath(Iterator<String> it,
            Collection<ImmutableNode> newNodes)
    {
        String nodeName = it.next();
        ImmutableNode.Builder builder;
        if (it.hasNext())
        {
            builder = new ImmutableNode.Builder(1);
            builder.addChild(createNodeOnPath(it, newNodes));
        }
        else
        {
            builder = new ImmutableNode.Builder(newNodes.size());
            builder.addChildren(newNodes);
        }
        return builder.name(nodeName).create();
    }

    /**
     * Initializes a transaction to clear the values of a property based on the
     * passed in collection of affected results.
     *
     * @param tx the transaction to be initialized
     * @param results a collection with results pointing to the nodes to be
     *        cleared
     * @return a flag whether there are elements to be cleared
     */
    private static boolean initializeClearTransaction(ModelTransaction tx,
            Collection<QueryResult<ImmutableNode>> results)
    {
        for (QueryResult<ImmutableNode> result : results)
        {
            if (result.isAttributeResult())
            {
                tx.addRemoveAttributeOperation(result.getNode(),
                        result.getAttributeName());
            }
            else
            {
                tx.addClearNodeValueOperation(result.getNode());
            }
        }

        return !results.isEmpty();
    }

    /**
     * Initializes a transaction to change the values of some query results
     * based on the passed in map.
     *
     * @param tx the transaction to be initialized
     * @param changedValues the map defining the elements to be changed
     * @return a flag whether there are elements to be updated
     */
    private static boolean initializeUpdateTransaction(ModelTransaction tx,
            Map<QueryResult<ImmutableNode>, Object> changedValues)
    {
        for (Map.Entry<QueryResult<ImmutableNode>, Object> e : changedValues
                .entrySet())
        {
            if (e.getKey().isAttributeResult())
            {
                tx.addAttributeOperation(e.getKey().getNode(), e.getKey()
                        .getAttributeName(), e.getValue());
            }
            else
            {
                tx.addChangeNodeValueOperation(e.getKey().getNode(),
                        e.getValue());
            }
        }

        return !changedValues.isEmpty();
    }

    /**
     * Determines the initial root node of this model. If a root node has been
     * provided, it is used. Otherwise, an empty dummy root node is created.
     *
     * @param providedRoot the passed in root node
     * @return the root node to be used
     */
    private static ImmutableNode initialRootNode(ImmutableNode providedRoot)
    {
        return (providedRoot != null) ? providedRoot
                : new ImmutableNode.Builder().create();
    }

    /**
     * Creates the mapping to parent nodes for the nodes structured represented
     * by the passed in root node. Each node is assigned its parent node. Here
     * an iterative algorithm is used rather than a recursive one to avoid stack
     * overflow for huge structures.
     *
     * @param root the root node of the structure
     * @return the parent node mapping
     */
    private Map<ImmutableNode, ImmutableNode> createParentMapping(
            ImmutableNode root)
    {
        Map<ImmutableNode, ImmutableNode> parents =
                new HashMap<ImmutableNode, ImmutableNode>();
        updateParentMapping(parents, root);
        return parents;
    }

    /**
     * Performs a non-blocking, thread-safe update of this model based on a
     * transaction initialized by the passed in initializer. This method uses
     * the atomic reference for the model's current data to ensure that an
     * update was successful even if the model is concurrently accessed.
     *
     * @param txInit the {@code TransactionInitializer}
     * @param selector an optional {@code NodeSelector} defining the target node
     *        of the transaction
     * @param resolver the {@code NodeKeyResolver}
     */
    private void updateModel(TransactionInitializer txInit,
            NodeSelector selector, NodeKeyResolver<ImmutableNode> resolver)
    {
        boolean done;

        do
        {
            TreeData currentData = getTreeData();
            done =
                    executeTransactionOnDetachedTrackedNode(txInit, selector,
                            currentData, resolver)
                            || executeTransactionOnCurrentStructure(txInit,
                                    selector, currentData, resolver);
        } while (!done);
    }

    /**
     * Executes a transaction on the current data of this model. This method is
     * called if an operation is to be executed on the model's root node or a
     * tracked node which is not yet detached.
     *
     * @param txInit the {@code TransactionInitializer}
     * @param selector an optional {@code NodeSelector} defining the target node
     * @param currentData the current data of the model
     * @param resolver the {@code NodeKeyResolver}
     * @return a flag whether the operation has been completed successfully
     */
    private boolean executeTransactionOnCurrentStructure(
            TransactionInitializer txInit, NodeSelector selector,
            TreeData currentData, NodeKeyResolver<ImmutableNode> resolver)
    {
        boolean done;
        ModelTransaction tx =
                new ModelTransaction(currentData, selector, resolver);
        if (!txInit.initTransaction(tx))
        {
            done = true;
        }
        else
        {
            TreeData newData = tx.execute();
            done = structure.compareAndSet(tx.getCurrentData(), newData);
        }
        return done;
    }

    /**
     * Tries to execute a transaction on the model of a detached tracked node.
     * This method checks whether the target node of the transaction is a
     * tracked node and if this node is already detached. If this is the case,
     * the update operation is independent on this model and has to be executed
     * on the specific model for the detached node.
     *
     * @param txInit the {@code TransactionInitializer}
     * @param selector an optional {@code NodeSelector} defining the target node
     * @param currentData the current data of the model
     * @param resolver the {@code NodeKeyResolver} @return a flag whether the
     *        transaction could be executed
     * @throws ConfigurationRuntimeException if the selector cannot be resolved
     */
    private boolean executeTransactionOnDetachedTrackedNode(
            TransactionInitializer txInit, NodeSelector selector,
            TreeData currentData, NodeKeyResolver<ImmutableNode> resolver)
    {
        if (selector != null)
        {
            InMemoryNodeModel detachedNodeModel =
                    currentData.getNodeTracker().getDetachedNodeModel(selector);
            if (detachedNodeModel != null)
            {
                detachedNodeModel.updateModel(txInit, null, resolver);
                return true;
            }
        }

        return false;
    }

    /**
     * Replaces a tracked node if it is already detached.
     *
     * @param currentData the current data of the model
     * @param selector the {@code NodeSelector} defining the tracked node
     * @param newNode the node replacing the tracked node
     * @return a flag whether the operation was successful
     */
    private boolean replaceDetachedTrackedNode(TreeData currentData,
            NodeSelector selector, ImmutableNode newNode)
    {
        InMemoryNodeModel detachedNodeModel =
                currentData.getNodeTracker().getDetachedNodeModel(selector);
        if (detachedNodeModel != null)
        {
            detachedNodeModel.setRootNode(newNode);
            return true;
        }

        return false;
    }

    /**
     * Replaces an active tracked node. The node then becomes detached.
     *
     * @param currentData the current data of the model
     * @param selector the {@code NodeSelector} defining the tracked node
     * @param newNode the node replacing the tracked node
     * @return a flag whether the operation was successful
     */
    private boolean replaceActiveTrackedNode(TreeData currentData,
            NodeSelector selector, ImmutableNode newNode)
    {
        NodeTracker newTracker =
                currentData.getNodeTracker().replaceAndDetachTrackedNode(
                        selector, newNode);
        return structure.compareAndSet(currentData,
                currentData.updateNodeTracker(newTracker));
    }

    /**
     * Creates tracked node entries for the specified nodes and creates the
     * corresponding selectors.
     *
     * @param refSelectors the reference where to store the selectors
     * @param nodes the nodes to be tracked
     * @param current the current {@code TreeData} object
     * @param resolver the {@code NodeKeyResolver}
     * @return the updated {@code TreeData} object
     */
    private static TreeData createSelectorsForTrackedNodes(
            Mutable<Collection<NodeSelector>> refSelectors,
            List<ImmutableNode> nodes, TreeData current,
            NodeKeyResolver<ImmutableNode> resolver)
    {
        List<NodeSelector> selectors =
                new ArrayList<NodeSelector>(nodes.size());
        Map<ImmutableNode, String> cache = new HashMap<ImmutableNode, String>();
        for (ImmutableNode node : nodes)
        {
            selectors.add(new NodeSelector(resolver.nodeKey(node, cache,
                    current)));
        }
        refSelectors.setValue(selectors);
        NodeTracker newTracker =
                current.getNodeTracker().trackNodes(selectors, nodes);
        return current.updateNodeTracker(newTracker);
    }

    /**
     * Checks whether the specified collection with values is not empty.
     *
     * @param values the collection with node values
     * @return <b>true</b> if values are provided, <b>false</b> otherwise
     */
    private static boolean valuesNotEmpty(Iterable<?> values)
    {
        return values.iterator().hasNext();
    }

    /**
     * Creates an exception referring to an invalid key for adding properties.
     * Such an exception is thrown when an operation tries to add something to
     * an attribute.
     *
     * @param key the invalid key causing this exception
     * @return the exception
     */
    private static RuntimeException attributeKeyException(String key)
    {
        return new IllegalArgumentException(
                "New nodes cannot be added to an attribute key: " + key);
    }

    /**
     * An interface used internally for handling concurrent updates. An
     * implementation has to populate the passed in {@code ModelTransaction}.
     * The transaction is then executed, and an atomic update of the model's
     * {@code TreeData} is attempted. If this fails - because another update
     * came across -, the whole operation has to be tried anew.
     */
    private static interface TransactionInitializer
    {
        /**
         * Initializes the specified transaction for an update operation. The
         * return value indicates whether the transaction should be executed. A
         * result of <b>false</b> means that the update is to be aborted (maybe
         * another update method was called).
         *
         * @param tx the transaction to be initialized
         * @return a flag whether the update should continue
         */
        boolean initTransaction(ModelTransaction tx);
    }
}