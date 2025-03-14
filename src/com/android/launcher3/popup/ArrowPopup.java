/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.launcher4.popup;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.CornerPathEffect;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.ShapeDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;

import com.android.launcher4.AbstractFloatingView;
import com.android.launcher4.Launcher;
import com.android.launcher4.LauncherAnimUtils;
import com.android.launcher4.R;
import com.android.launcher4.Utilities;
import com.android.launcher4.anim.RevealOutlineAnimation;
import com.android.launcher4.anim.RoundedRectRevealOutlineProvider;
import com.android.launcher4.dragndrop.DragLayer;
import com.android.launcher4.graphics.TriangleShape;
import com.android.launcher4.util.Themes;

import java.util.ArrayList;
import java.util.Collections;

/**
 * A container for shortcuts to deep links and notifications associated with an app.
 */
public abstract class ArrowPopup extends AbstractFloatingView {

    private final Rect mTempRect = new Rect();

    protected final LayoutInflater mInflater;
    private final float mOutlineRadius;
    protected final Launcher mLauncher;
    protected final boolean mIsRtl;

    private final int mArrayOffset;
    private final View mArrow;

    protected boolean mIsLeftAligned;
    protected boolean mIsAboveIcon;
    private int mGravity;

    protected Animator mOpenCloseAnimator;
    protected boolean mDeferContainerRemoval;
    private final Rect mStartRect = new Rect();
    private final Rect mEndRect = new Rect();

    public ArrowPopup(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mInflater = LayoutInflater.from(context);
        mOutlineRadius = getResources().getDimension(R.dimen.bg_round_rect_radius);
        mLauncher = Launcher.getLauncher(context);
        mIsRtl = Utilities.isRtl(getResources());

        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mOutlineRadius);
            }
        });

        // Initialize arrow view
        final Resources resources = getResources();
        final int arrowWidth = resources.getDimensionPixelSize(R.dimen.popup_arrow_width);
        final int arrowHeight = resources.getDimensionPixelSize(R.dimen.popup_arrow_height);
        mArrow = new View(context);
        mArrow.setLayoutParams(new DragLayer.LayoutParams(arrowWidth, arrowHeight));
        mArrayOffset = resources.getDimensionPixelSize(R.dimen.popup_arrow_vertical_offset);
    }

    public ArrowPopup(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ArrowPopup(Context context) {
        this(context, null, 0);
    }

    @Override
    protected void handleClose(boolean animate) {
        if (animate) {
            animateClose();
        } else {
            closeComplete();
        }
    }

    public <T extends View> T inflateAndAdd(int resId, ViewGroup container) {
        View view = mInflater.inflate(resId, container, false);
        container.addView(view);
        return (T) view;
    }

    /**
     * Called when all view inflation and reordering in complete.
     */
    protected void onInflationComplete(boolean isReversed) { }

    /**
     * Shows the popup at the desired location, optionally reversing the children.
     * @param viewsToFlip number of views from the top to to flip in case of reverse order
     */
    protected void reorderAndShow(int viewsToFlip) {
        setVisibility(View.INVISIBLE);
        mIsOpen = true;
        mLauncher.getDragLayer().addView(this);
        orientAboutObject();

        boolean reverseOrder = mIsAboveIcon;
        if (reverseOrder) {
            int count = getChildCount();
            ArrayList<View> allViews = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (i == viewsToFlip) {
                    Collections.reverse(allViews);
                }
                allViews.add(getChildAt(i));
            }
            Collections.reverse(allViews);
            removeAllViews();
            for (int i = 0; i < count; i++) {
                addView(allViews.get(i));
            }

            orientAboutObject();
        }
        onInflationComplete(reverseOrder);

        // Add the arrow.
        final Resources res = getResources();
        final int arrowCenterOffset = res.getDimensionPixelSize(isAlignedWithStart()
                ? R.dimen.popup_arrow_horizontal_center_start
                : R.dimen.popup_arrow_horizontal_center_end);
        final int halfArrowWidth = res.getDimensionPixelSize(R.dimen.popup_arrow_width) / 2;
        mLauncher.getDragLayer().addView(mArrow);
        DragLayer.LayoutParams arrowLp = (DragLayer.LayoutParams) mArrow.getLayoutParams();
        if (mIsLeftAligned) {
            mArrow.setX(getX() + arrowCenterOffset - halfArrowWidth);
        } else {
            mArrow.setX(getX() + getMeasuredWidth() - arrowCenterOffset - halfArrowWidth);
        }

        if (Gravity.isVertical(mGravity)) {
            // This is only true if there wasn't room for the container next to the icon,
            // so we centered it instead. In that case we don't want to showDefaultOptions the arrow.
            mArrow.setVisibility(INVISIBLE);
        } else {
            ShapeDrawable arrowDrawable = new ShapeDrawable(TriangleShape.create(
                    arrowLp.width, arrowLp.height, !mIsAboveIcon));
            Paint arrowPaint = arrowDrawable.getPaint();
            arrowPaint.setColor(Themes.getAttrColor(mLauncher, R.attr.popupColorPrimary));
            // The corner path effect won't be reflected in the shadow, but shouldn't be noticeable.
            int radius = getResources().getDimensionPixelSize(R.dimen.popup_arrow_corner_radius);
            arrowPaint.setPathEffect(new CornerPathEffect(radius));
            mArrow.setBackground(arrowDrawable);
            mArrow.setElevation(getElevation());
        }

        mArrow.setPivotX(arrowLp.width / 2);
        mArrow.setPivotY(mIsAboveIcon ? 0 : arrowLp.height);

        animateOpen();
    }

    protected boolean isAlignedWithStart() {
        return mIsLeftAligned && !mIsRtl || !mIsLeftAligned && mIsRtl;
    }

    /**
     * Provide the location of the target object relative to the dragLayer.
     */
    protected abstract void getTargetObjectLocation(Rect outPos);

    /**
     * Orients this container above or below the given icon, aligning with the left or right.
     *
     * These are the preferred orientations, in order (RTL prefers right-aligned over left):
     * - Above and left-aligned
     * - Above and right-aligned
     * - Below and left-aligned
     * - Below and right-aligned
     *
     * So we always align left if there is enough horizontal space
     * and align above if there is enough vertical space.
     */
    protected void orientAboutObject() {
        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        int width = getMeasuredWidth();
        int extraVerticalSpace = mArrow.getLayoutParams().height + mArrayOffset
                + getResources().getDimensionPixelSize(R.dimen.popup_vertical_padding);
        int height = getMeasuredHeight() + extraVerticalSpace;

        getTargetObjectLocation(mTempRect);
        DragLayer dragLayer = mLauncher.getDragLayer();
        Rect insets = dragLayer.getInsets();

        // Align left (right in RTL) if there is room.
        int leftAlignedX = mTempRect.left;
        int rightAlignedX = mTempRect.right - width;
        int x = leftAlignedX;
        boolean canBeLeftAligned = leftAlignedX + width + insets.left
                < dragLayer.getRight() - insets.right;
        boolean canBeRightAligned = rightAlignedX > dragLayer.getLeft() + insets.left;
        if (!canBeLeftAligned || (mIsRtl && canBeRightAligned)) {
            x = rightAlignedX;
        }
        mIsLeftAligned = x == leftAlignedX;

        // Offset x so that the arrow and shortcut icons are center-aligned with the original icon.
        int iconWidth = mTempRect.width();
        Resources resources = getResources();
        int xOffset;
        if (isAlignedWithStart()) {
            // Aligning with the shortcut icon.
            int shortcutIconWidth = resources.getDimensionPixelSize(R.dimen.deep_shortcut_icon_size);
            int shortcutPaddingStart = resources.getDimensionPixelSize(
                    R.dimen.popup_padding_start);
            xOffset = iconWidth / 2 - shortcutIconWidth / 2 - shortcutPaddingStart;
        } else {
            // Aligning with the drag handle.
            int shortcutDragHandleWidth = resources.getDimensionPixelSize(
                    R.dimen.deep_shortcut_drag_handle_size);
            int shortcutPaddingEnd = resources.getDimensionPixelSize(
                    R.dimen.popup_padding_end);
            xOffset = iconWidth / 2 - shortcutDragHandleWidth / 2 - shortcutPaddingEnd;
        }
        x += mIsLeftAligned ? xOffset : -xOffset;

        // Open above icon if there is room.
        int iconHeight = mTempRect.height();
        int y = mTempRect.top - height;
        mIsAboveIcon = y > dragLayer.getTop() + insets.top;
        if (!mIsAboveIcon) {
            y = mTempRect.top + iconHeight + extraVerticalSpace;
        }

        // Insets are added later, so subtract them now.
        x -= insets.left;
        y -= insets.top;

        mGravity = 0;
        if (y + height > dragLayer.getBottom() - insets.bottom) {
            // The container is opening off the screen, so just center it in the drag layer instead.
            mGravity = Gravity.CENTER_VERTICAL;
            // Put the container next to the icon, preferring the right side in ltr (left in rtl).
            int rightSide = leftAlignedX + iconWidth - insets.left;
            int leftSide = rightAlignedX - iconWidth - insets.left;
            if (!mIsRtl) {
                if (rightSide + width < dragLayer.getRight()) {
                    x = rightSide;
                    mIsLeftAligned = true;
                } else {
                    x = leftSide;
                    mIsLeftAligned = false;
                }
            } else {
                if (leftSide > dragLayer.getLeft()) {
                    x = leftSide;
                    mIsLeftAligned = false;
                } else {
                    x = rightSide;
                    mIsLeftAligned = true;
                }
            }
            mIsAboveIcon = true;
        }

        setX(x);
        if (Gravity.isVertical(mGravity)) {
            return;
        }

        DragLayer.LayoutParams lp = (DragLayer.LayoutParams) getLayoutParams();
        DragLayer.LayoutParams arrowLp = (DragLayer.LayoutParams) mArrow.getLayoutParams();
        if (mIsAboveIcon) {
            arrowLp.gravity = lp.gravity = Gravity.BOTTOM;
            lp.bottomMargin =
                    mLauncher.getDragLayer().getHeight() - y - getMeasuredHeight() - insets.top;
            arrowLp.bottomMargin = lp.bottomMargin - arrowLp.height - mArrayOffset - insets.bottom;
        } else {
            arrowLp.gravity = lp.gravity = Gravity.TOP;
            lp.topMargin = y + insets.top;
            arrowLp.topMargin = lp.topMargin - insets.top - arrowLp.height - mArrayOffset;
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        // enforce contained is within screen
        DragLayer dragLayer = mLauncher.getDragLayer();
        if (getTranslationX() + l < 0 || getTranslationX() + r > dragLayer.getWidth()) {
            // If we are still off screen, center horizontally too.
            mGravity |= Gravity.CENTER_HORIZONTAL;
        }

        if (Gravity.isHorizontal(mGravity)) {
            setX(dragLayer.getWidth() / 2 - getMeasuredWidth() / 2);
            mArrow.setVisibility(INVISIBLE);
        }
        if (Gravity.isVertical(mGravity)) {
            setY(dragLayer.getHeight() / 2 - getMeasuredHeight() / 2);
        }
    }

    private void animateOpen() {
        setVisibility(View.VISIBLE);

        final AnimatorSet openAnim = LauncherAnimUtils.createAnimatorSet();
        final Resources res = getResources();
        final long revealDuration = (long) res.getInteger(R.integer.config_popupOpenCloseDuration);
        final TimeInterpolator revealInterpolator = new AccelerateDecelerateInterpolator();

        // Rectangular reveal.
        final ValueAnimator revealAnim = createOpenCloseOutlineProvider()
                .createRevealAnimator(this, false);
        revealAnim.setDuration(revealDuration);
        revealAnim.setInterpolator(revealInterpolator);

        Animator fadeIn = ObjectAnimator.ofFloat(this, ALPHA, 0, 1);
        fadeIn.setDuration(revealDuration);
        fadeIn.setInterpolator(revealInterpolator);
        openAnim.play(fadeIn);

        // Animate the arrow.
        mArrow.setScaleX(0);
        mArrow.setScaleY(0);
        Animator arrowScale = ObjectAnimator.ofFloat(mArrow, LauncherAnimUtils.SCALE_PROPERTY, 1)
                .setDuration(res.getInteger(R.integer.config_popupArrowOpenDuration));

        openAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                announceAccessibilityChanges();
                mOpenCloseAnimator = null;
            }
        });

        mOpenCloseAnimator = openAnim;
        openAnim.playSequentially(revealAnim, arrowScale);
        openAnim.start();
    }

    protected void animateClose() {
        if (!mIsOpen) {
            return;
        }
        mEndRect.setEmpty();
        if (getOutlineProvider() instanceof RevealOutlineAnimation) {
            ((RevealOutlineAnimation) getOutlineProvider()).getOutline(mEndRect);
        }
        if (mOpenCloseAnimator != null) {
            mOpenCloseAnimator.cancel();
        }
        mIsOpen = false;

        final AnimatorSet closeAnim = LauncherAnimUtils.createAnimatorSet();
        // Hide the arrow
        closeAnim.play(ObjectAnimator.ofFloat(mArrow, LauncherAnimUtils.SCALE_PROPERTY, 0));
        closeAnim.play(ObjectAnimator.ofFloat(mArrow, ALPHA, 0));

        final Resources res = getResources();
        final TimeInterpolator revealInterpolator = new AccelerateDecelerateInterpolator();

        // Rectangular reveal (reversed).
        final ValueAnimator revealAnim = createOpenCloseOutlineProvider()
                .createRevealAnimator(this, true);
        revealAnim.setInterpolator(revealInterpolator);
        closeAnim.play(revealAnim);

        Animator fadeOut = ObjectAnimator.ofFloat(this, ALPHA, 0);
        fadeOut.setInterpolator(revealInterpolator);
        closeAnim.play(fadeOut);

        onCreateCloseAnimation(closeAnim);
        closeAnim.setDuration((long) res.getInteger(R.integer.config_popupOpenCloseDuration));
        closeAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mOpenCloseAnimator = null;
                if (mDeferContainerRemoval) {
                    setVisibility(INVISIBLE);
                } else {
                    closeComplete();
                }
            }
        });
        mOpenCloseAnimator = closeAnim;
        closeAnim.start();
    }

    /**
     * Called when creating the close transition allowing subclass can add additional animations.
     */
    protected void onCreateCloseAnimation(AnimatorSet anim) { }

    private RoundedRectRevealOutlineProvider createOpenCloseOutlineProvider() {
        int arrowCenterX = getResources().getDimensionPixelSize(mIsLeftAligned ^ mIsRtl ?
                R.dimen.popup_arrow_horizontal_center_start:
                R.dimen.popup_arrow_horizontal_center_end);
        if (!mIsLeftAligned) {
            arrowCenterX = getMeasuredWidth() - arrowCenterX;
        }
        int arrowCenterY = mIsAboveIcon ? getMeasuredHeight() : 0;

        mStartRect.set(arrowCenterX, arrowCenterY, arrowCenterX, arrowCenterY);
        if (mEndRect.isEmpty()) {
            mEndRect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
        }

        return new RoundedRectRevealOutlineProvider
                (mOutlineRadius, mOutlineRadius, mStartRect, mEndRect);
    }

    /**
     * Closes the popup without animation.
     */
    protected void closeComplete() {
        if (mOpenCloseAnimator != null) {
            mOpenCloseAnimator.cancel();
            mOpenCloseAnimator = null;
        }
        mIsOpen = false;
        mDeferContainerRemoval = false;
        mLauncher.getDragLayer().removeView(this);
        mLauncher.getDragLayer().removeView(mArrow);
    }
}
