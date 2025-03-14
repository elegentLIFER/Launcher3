/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.launcher4.shortcuts;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.Toast;

import com.android.launcher4.BubbleTextView;
import com.android.launcher4.R;
import com.android.launcher4.Utilities;

/**
 * A {@link BubbleTextView} that has the shortcut icon on the left and drag handle on the right.
 */
public class DeepShortcutTextView extends BubbleTextView {
    private final Rect mDragHandleBounds = new Rect();
    private final int mDragHandleWidth;
    private boolean mShowInstructionToast = false;

    private Toast mInstructionToast;

    public DeepShortcutTextView(Context context) {
        this(context, null, 0);
    }

    public DeepShortcutTextView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DeepShortcutTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        Resources resources = getResources();
        mDragHandleWidth = resources.getDimensionPixelSize(R.dimen.popup_padding_end)
                + resources.getDimensionPixelSize(R.dimen.deep_shortcut_drag_handle_size)
                + resources.getDimensionPixelSize(R.dimen.deep_shortcut_drawable_padding) / 2;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        mDragHandleBounds.set(0, 0, mDragHandleWidth, getMeasuredHeight());
        if (!Utilities.isRtl(getResources())) {
            mDragHandleBounds.offset(getMeasuredWidth() - mDragHandleBounds.width(), 0);
        }
    }

    @Override
    protected void applyCompoundDrawables(Drawable icon) {
        // The icon is drawn in a separate view.
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            // Show toast if user touches the drag handle (long clicks still start the drag).
            mShowInstructionToast = mDragHandleBounds.contains((int) ev.getX(), (int) ev.getY());
        }
        return super.onTouchEvent(ev);
    }

    @Override
    public boolean performClick() {
        if (mShowInstructionToast) {
            showToast();
            return true;
        }
        return super.performClick();
    }

    private void showToast() {
        if (mInstructionToast != null) {
            mInstructionToast.cancel();
        }
        CharSequence msg = Utilities.wrapForTts(
                getContext().getText(R.string.long_press_shortcut_to_add),
                getContext().getString(R.string.long_accessible_way_to_add_shortcut));
        mInstructionToast = Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT);
        mInstructionToast.show();
    }
}
