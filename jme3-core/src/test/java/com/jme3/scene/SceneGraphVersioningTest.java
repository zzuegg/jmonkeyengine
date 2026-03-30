/*
 * Copyright (c) 2024 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.jme3.scene;

import com.jme3.math.Vector3f;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for the scene-graph versioning feature introduced via
 * {@link Spatial#getLastGeomChangeId()}.
 *
 * <p>The feature assigns a monotonically-increasing change ID to every
 * {@link Spatial}.  The ID is updated (and propagated upward to all ancestors)
 * whenever any geometric state changes: transform, bounding volume, lights, or
 * material-parameter overrides.  This lets callers cheaply decide whether a
 * sub-tree needs re-processing (e.g. culling) between frames.
 */
public class SceneGraphVersioningTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Minimal leaf Geometry that does not need a real mesh for these tests. */
    private static Geometry makeGeom(String name) {
        return new Geometry(name);
    }

    // -----------------------------------------------------------------------
    // 1. Initial state
    // -----------------------------------------------------------------------

    /**
     * A freshly created spatial must have a positive change ID because
     * construction itself represents an initial state.
     */
    @Test
    public void freshSpatialHasPositiveChangeId() {
        Node node = new Node("root");
        assertTrue("Fresh spatial should have a positive change ID",
                node.getLastGeomChangeId() > 0);
    }

    /**
     * Every new spatial must receive a unique (strictly increasing) initial ID.
     */
    @Test
    public void eachNewSpatialHasUniqueChangeId() {
        Node a = new Node("a");
        Node b = new Node("b");
        // IDs are assigned from a monotonic counter, so a's ID < b's ID.
        assertTrue("Second spatial should have a higher initial ID than first",
                b.getLastGeomChangeId() > a.getLastGeomChangeId());
    }

    // -----------------------------------------------------------------------
    // 2. Transform changes propagate upward
    // -----------------------------------------------------------------------

    /**
     * Changing a child's local translation must increase the change ID on
     * the child AND on all its ancestors up to the root.
     */
    @Test
    public void transformChangePropagatesUp() {
        Node root = new Node("root");
        Node mid  = new Node("mid");
        Geometry leaf = makeGeom("leaf");

        root.attachChild(mid);
        mid.attachChild(leaf);

        long rootBefore = root.getLastGeomChangeId();
        long midBefore  = mid.getLastGeomChangeId();
        long leafBefore = leaf.getLastGeomChangeId();

        // Mutate the leaf's transform.
        leaf.setLocalTranslation(new Vector3f(1f, 2f, 3f));

        assertTrue("leaf change ID should increase after translation",
                leaf.getLastGeomChangeId() > leafBefore);
        assertTrue("mid change ID should increase after leaf translation",
                mid.getLastGeomChangeId() > midBefore);
        assertTrue("root change ID should increase after leaf translation",
                root.getLastGeomChangeId() > rootBefore);
    }

    /**
     * A transform change on an intermediate node must update the node itself
     * and the root, but NOT siblings of the changed node.
     */
    @Test
    public void transformChangeDoesNotAffectSiblings() {
        Node root    = new Node("root");
        Node branch1 = new Node("branch1");
        Node branch2 = new Node("branch2");

        root.attachChild(branch1);
        root.attachChild(branch2);

        long branch2Before = branch2.getLastGeomChangeId();

        // Change only branch1.
        branch1.setLocalTranslation(1f, 0f, 0f);

        assertEquals("Sibling branch2 should NOT be affected by branch1 change",
                branch2Before, branch2.getLastGeomChangeId());
    }

    // -----------------------------------------------------------------------
    // 3. No spurious updates when nothing changes
    // -----------------------------------------------------------------------

    /**
     * Reading a spatial without modifying it must not alter its change ID.
     */
    @Test
    public void noChangeWhenOnlyReading() {
        Node root = new Node("root");
        long idBefore = root.getLastGeomChangeId();

        // Pure reads – should not cause any side effects.
        root.getName();
        root.getWorldTranslation();
        root.getWorldBound();

        assertEquals("Read-only access must not alter the change ID",
                idBefore, root.getLastGeomChangeId());
    }

    // -----------------------------------------------------------------------
    // 4. Attach / detach children
    // -----------------------------------------------------------------------

    /**
     * Attaching a child must increase the change ID on the parent because
     * the sub-tree geometry has changed.
     */
    @Test
    public void attachChildIncreasesParentChangeId() {
        Node parent = new Node("parent");
        Node child  = new Node("child");

        long parentBefore = parent.getLastGeomChangeId();
        parent.attachChild(child);

        assertTrue("Attaching a child should increase parent's change ID",
                parent.getLastGeomChangeId() > parentBefore);
    }

    /**
     * Detaching a child must also increase the parent's change ID.
     */
    @Test
    public void detachChildIncreasesParentChangeId() {
        Node parent = new Node("parent");
        Node child  = new Node("child");
        parent.attachChild(child);

        long parentBefore = parent.getLastGeomChangeId();
        parent.detachChild(child);

        assertTrue("Detaching a child should increase parent's change ID",
                parent.getLastGeomChangeId() > parentBefore);
    }

    // -----------------------------------------------------------------------
    // 5. Deep tree propagation
    // -----------------------------------------------------------------------

    /**
     * A change deep inside a multi-level tree must surface all the way to
     * the root node.
     */
    @Test
    public void deepChangeReachesRoot() {
        Node root  = new Node("root");
        Node l1    = new Node("l1");
        Node l2    = new Node("l2");
        Node l3    = new Node("l3");
        Geometry g = makeGeom("leaf");

        root.attachChild(l1);
        l1.attachChild(l2);
        l2.attachChild(l3);
        l3.attachChild(g);

        long rootBefore = root.getLastGeomChangeId();

        // Change the deep leaf.
        g.setLocalTranslation(5f, 0f, 0f);

        assertTrue("Root ID must increase after a change 4 levels deep",
                root.getLastGeomChangeId() > rootBefore);
    }

    // -----------------------------------------------------------------------
    // 6. Monotonically increasing IDs
    // -----------------------------------------------------------------------

    /**
     * Multiple consecutive changes must each produce an ID strictly greater
     * than the previous one.
     */
    @Test
    public void consecutiveChangesYieldIncreasingIds() {
        Node node = new Node("node");

        long id1 = node.getLastGeomChangeId();
        node.setLocalTranslation(1f, 0f, 0f);
        long id2 = node.getLastGeomChangeId();
        node.setLocalTranslation(2f, 0f, 0f);
        long id3 = node.getLastGeomChangeId();

        assertTrue("Second change ID should be greater than first", id2 > id1);
        assertTrue("Third change ID should be greater than second", id3 > id2);
    }

    // -----------------------------------------------------------------------
    // 7. Typical frame-skipping usage pattern
    // -----------------------------------------------------------------------

    /**
     * Demonstrates the intended usage: snapshot the change ID before
     * rendering and compare at the next frame to decide whether to re-cull.
     */
    @Test
    public void frameSkipPattern() {
        Node root = new Node("root");
        Geometry g = makeGeom("geom");
        root.attachChild(g);

        // "Frame 1" – record the ID after the initial scene setup.
        long frameId = root.getLastGeomChangeId();

        // No changes between frame 1 and frame 2.
        long newFrameId = root.getLastGeomChangeId();
        assertEquals("No culling work needed if the ID has not changed",
                frameId, newFrameId);

        // Something changes in "frame 3".
        g.setLocalTranslation(1f, 2f, 3f);
        newFrameId = root.getLastGeomChangeId();
        assertNotEquals("Culling must be re-run when the ID changes",
                frameId, newFrameId);

        // Record the new baseline and verify the next frame is stable again.
        frameId = newFrameId;
        assertEquals("No culling work needed if the ID has not changed again",
                frameId, root.getLastGeomChangeId());
    }
}
