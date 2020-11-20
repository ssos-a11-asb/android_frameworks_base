/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.pip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.ComponentName;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Size;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.BiConsumer;

/**
 * Tests for {@link PipBoundsState}.
 */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class PipBoundsStateTest extends ShellTestCase {

    private static final Rect DEFAULT_BOUNDS = new Rect(0, 0, 10, 10);
    private static final float DEFAULT_SNAP_FRACTION = 1.0f;

    private PipBoundsState mPipBoundsState;
    private ComponentName mTestComponentName1;
    private ComponentName mTestComponentName2;

    @Before
    public void setUp() {
        mPipBoundsState = new PipBoundsState(mContext);
        mTestComponentName1 = new ComponentName(mContext, "component1");
        mTestComponentName2 = new ComponentName(mContext, "component2");
    }

    @Test
    public void testSetBounds() {
        final Rect bounds = new Rect(0, 0, 100, 100);
        mPipBoundsState.setBounds(bounds);

        assertEquals(bounds, mPipBoundsState.getBounds());
    }

    @Test
    public void testSetReentryState() {
        final Rect bounds = new Rect(0, 0, 100, 100);
        final float snapFraction = 0.5f;

        mPipBoundsState.saveReentryState(bounds, snapFraction);

        final PipBoundsState.PipReentryState state = mPipBoundsState.getReentryState();
        assertEquals(new Size(100, 100), state.getSize());
        assertEquals(snapFraction, state.getSnapFraction(), 0.01);
    }

    @Test
    public void testClearReentryState() {
        final Rect bounds = new Rect(0, 0, 100, 100);
        final float snapFraction = 0.5f;

        mPipBoundsState.saveReentryState(bounds, snapFraction);
        mPipBoundsState.clearReentryState();

        assertNull(mPipBoundsState.getReentryState());
    }

    @Test
    public void testSetLastPipComponentName_notChanged_doesNotClearReentryState() {
        mPipBoundsState.setLastPipComponentName(mTestComponentName1);
        mPipBoundsState.saveReentryState(DEFAULT_BOUNDS, DEFAULT_SNAP_FRACTION);

        mPipBoundsState.setLastPipComponentName(mTestComponentName1);

        final PipBoundsState.PipReentryState state = mPipBoundsState.getReentryState();
        assertNotNull(state);
        assertEquals(new Size(DEFAULT_BOUNDS.width(), DEFAULT_BOUNDS.height()), state.getSize());
        assertEquals(DEFAULT_SNAP_FRACTION, state.getSnapFraction(), 0.01);
    }

    @Test
    public void testSetLastPipComponentName_changed_clearReentryState() {
        mPipBoundsState.setLastPipComponentName(mTestComponentName1);
        mPipBoundsState.saveReentryState(DEFAULT_BOUNDS, DEFAULT_SNAP_FRACTION);

        mPipBoundsState.setLastPipComponentName(mTestComponentName2);

        assertNull(mPipBoundsState.getReentryState());
    }

    @Test
    public void testSetShelfVisibility_changed_callbackInvoked() {
        final BiConsumer<Boolean, Integer> callback = mock(BiConsumer.class);
        mPipBoundsState.setOnShelfVisibilityChangeCallback(callback);

        mPipBoundsState.setShelfVisibility(true, 100);

        verify(callback).accept(true, 100);
    }

    @Test
    public void testSetShelfVisibility_notChanged_callbackNotInvoked() {
        final BiConsumer<Boolean, Integer> callback = mock(BiConsumer.class);
        mPipBoundsState.setShelfVisibility(true, 100);
        mPipBoundsState.setOnShelfVisibilityChangeCallback(callback);

        mPipBoundsState.setShelfVisibility(true, 100);

        verify(callback, never()).accept(true, 100);
    }

    @Test
    public void testSetOverrideMinSize_changed_callbackInvoked() {
        final Runnable callback = mock(Runnable.class);
        mPipBoundsState.setOverrideMinSize(new Size(5, 5));
        mPipBoundsState.setOnMinimalSizeChangeCallback(callback);

        mPipBoundsState.setOverrideMinSize(new Size(10, 10));

        verify(callback).run();
    }

    @Test
    public void testSetOverrideMinSize_notChanged_callbackNotInvoked() {
        final Runnable callback = mock(Runnable.class);
        mPipBoundsState.setOverrideMinSize(new Size(5, 5));
        mPipBoundsState.setOnMinimalSizeChangeCallback(callback);

        mPipBoundsState.setOverrideMinSize(new Size(5, 5));

        verify(callback, never()).run();
    }

    @Test
    public void testGetOverrideMinEdgeSize() {
        mPipBoundsState.setOverrideMinSize(null);
        assertEquals(0, mPipBoundsState.getOverrideMinEdgeSize());

        mPipBoundsState.setOverrideMinSize(new Size(5, 10));
        assertEquals(5, mPipBoundsState.getOverrideMinEdgeSize());

        mPipBoundsState.setOverrideMinSize(new Size(15, 10));
        assertEquals(10, mPipBoundsState.getOverrideMinEdgeSize());
    }
}
